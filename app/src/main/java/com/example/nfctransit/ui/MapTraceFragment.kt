package com.example.nfctransit.ui

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.nfctransit.MainActivity
import com.example.nfctransit.R
import com.example.nfctransit.data.TransitData
import com.example.nfctransit.data.prefs.CurrentTripRouteDisplayMode
import com.example.nfctransit.data.route.RouteGeometryKind
import com.example.nfctransit.data.route.RouteLeg
import com.example.nfctransit.data.route.RouteLoadState
import com.example.nfctransit.data.route.RouteMode
import com.example.nfctransit.data.route.RoutePlan
import com.example.nfctransit.data.route.TransitRouteQuery
import com.example.nfctransit.data.route.TransitRouteRepository
import com.example.nfctransit.databinding.FragmentMapTraceBinding
import com.tencent.tencentmap.mapsdk.maps.CameraUpdateFactory
import com.tencent.tencentmap.mapsdk.maps.MapView
import com.tencent.tencentmap.mapsdk.maps.TencentMap
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptor
import com.tencent.tencentmap.mapsdk.maps.model.BitmapDescriptorFactory
import com.tencent.tencentmap.mapsdk.maps.model.CameraPosition
import com.tencent.tencentmap.mapsdk.maps.model.LatLng
import com.tencent.tencentmap.mapsdk.maps.model.LatLngBounds
import com.tencent.tencentmap.mapsdk.maps.model.Marker
import com.tencent.tencentmap.mapsdk.maps.model.MarkerOptions
import com.tencent.tencentmap.mapsdk.maps.model.OverlayLevel
import com.tencent.tencentmap.mapsdk.maps.model.Polyline
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun shouldShowFullCurrentTripRoute(
    mode: CurrentTripRouteDisplayMode,
    resolvedTransitLegCount: Int
): Boolean = mode == CurrentTripRouteDisplayMode.FULL_TRANSFERS && resolvedTransitLegCount > 0

/**
 * 地图轨迹：腾讯地图展示行程。从数据库交易构建时间线（MapJourney），
 * 优先绘制 Direction 公交方案的乘车/步行路线，失败时退回示意曲线；
 * 播放时高亮当前站并让相机沿路线缓慢移动。
 */
class MapTraceFragment : Fragment(R.layout.fragment_map_trace) {

    /**
     * 一条线路腿的覆盖物。白色描边 [white] 只在当前段按需创建、离开即移除：
     * 腾讯 SDK 的 Polyline 不认 visible，且颜色 0（透明）会回退成默认样式（灰边白芯粗线）。
     */
    private data class LegOverlay(
        var white: Polyline?,
        val points: List<LatLng>,
        val color: Polyline,
        val leg: RouteLeg?,
        val activeColor: Int,
        val activeWidthDp: Float,
        val geometryKind: RouteGeometryKind = RouteGeometryKind.FULL_POLYLINE,
        /** 示意曲线当前态：起点线路色 → 终点线路色渐变（两端同色/缺色时为 null，用单色） */
        val gradientEnds: Pair<Int, Int>? = null
    ) {
        var animator: ValueAnimator? = null
        /** 折线已移除：之后任何颜色/线宽更新都必须跳过（对已释放的折线调用会在地图引擎内原生崩溃） */
        var removed = false
        /** 当前显示的颜色：单色为 1 个元素，渐变为逐点颜色 */
        var shownColors: IntArray = intArrayOf(0)
        /** 当前段的基础颜色（流光叠加在其上）；非当前段为 null */
        var activeColors: IntArray? = null
    }

    private data class SegmentOverlay(
        val segment: MapSegment,
        val legs: MutableList<LegOverlay>,
        var realRoute: Boolean = false
    )

    private var _binding: FragmentMapTraceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var mapView: MapView? = null
    private var tencentMap: TencentMap? = null

    private var model: JourneyModel = JourneyModel.build(emptyList())
    private var currentEventIndex = 0
    private var playing = false
    private var speed = 1f
    private var mainAccent = Palette.ACCENT
    private var currentTripRouteDisplayMode = CurrentTripRouteDisplayMode.ENDPOINTS_ONLY
    private val routeRepository by lazy { TransitRouteRepository(requireContext().applicationContext) }

    private val stationMarkers = mutableMapOf<Long, Marker>()
    private val stationMeta = mutableMapOf<Long, Pair<String, Int>>()   // stationId -> (站名, 圆点色)
    // 同名且几乎同址的站点（换乘站在不同线路/城市下各有一条记录）合并为一个圆点：stationId -> 圆点所属 stationId
    private val stationMarkerKey = mutableMapOf<Long, Long>()
    private val stationIconKeys = mutableMapOf<Long, String>()   // 圆点当前图标状态；变化时才重建标记
    private val segmentOverlays = mutableListOf<SegmentOverlay>()
    private val routePlans = mutableMapOf<MapSegment, RoutePlan>()
    private val segmentRows = mutableListOf<View>()          // 行程列表行（与 model.segments 对齐）
    // 行高亮渐变（动画 + 目标 alpha）：每行至多一个，改状态前先取消，避免快速滑动时旧渐变稍后把行又点亮
    private val rowFades = HashMap<View, Pair<ObjectAnimator, Int>>()
    private val plainDotCache = mutableMapOf<Int, BitmapDescriptor>()
    private val highlightDotCache = mutableMapOf<Int, BitmapDescriptor>()
    private val labelCache = mutableMapOf<String, BitmapDescriptor>()  // "站名|颜色|高亮" -> 带站名标签的圆点
    private var namesShown = false     // 当前 zoom≥13 是否显示站名标签
    private var dotRadiusDp = 4.5f     // 当前普通圆点半径（随缩放变化）
    private var needIconRefresh = false   // 相机移动中标记，移动结束后一次性重建图标

    // 腾讯 SDK 按预乘 alpha 混合折线颜色，半透明色会被抬亮成白色；折线一律用"预先与底图混好"的不透明色
    private val ghostCurveColor = mapTint(Palette.INK_3, 0.30f)   // 非当前段示意曲线：浅中性灰

    private var programmaticScroll = false   // 播放自动滚动列表时不当作"用户滑动"
    private var scrollAnimator: ValueAnimator? = null
    private var lastListTouchAt = 0L   // 最近一次触摸行程列表的时间（uptime），用于区分用户滚动
    private var lastActiveSegment: MapSegment? = null   // 用于判断当前段是否切换（切换时做过渡动画）
    private var highlightedRow = -1
    private var shimmerAnimator: ValueAnimator? = null   // 当前段流光（指示行进方向）
    private var progressAnimator: ValueAnimator? = null  // 进度条宽度过渡
    private var routeClock: ValueAnimator? = null        // 播放中：进度条连续推进
    private var ringClock: ValueAnimator? = null         // 播放中：播放按钮倒计时环（仅镜头静止期间）
    private var departing = false                        // 播放中镜头已起飞前往下一段：地图上当前段先恢复为非激活
    private var playRing: PlaybackRingDrawable? = null
    private var haptics: PlaybackHaptics? = null
    private var renderedEventIndex = -1                  // 播放卡片当前展示的事件（切换时做入场动画）
    private var renderedSegment: MapSegment? = null      // 播放卡片当前展示的行程（同一行程进/出站切换只淡入时间）
    // 流光：自外向内逐层变短变亮；每层按所经过的腿切成多段纯色折线（各段取下面线路的颜色）
    private val shimmerLayers = Array(SHIMMER_LAYERS) { mutableListOf<Polyline>() }
    private var playbackJob: Job? = null
    private var cameraJob: Job? = null
    private var routeLoadJob: Job? = null
    private var routeGeneration = 0
    private var routeTotal = 0
    private var routeFinished = 0
    private var routeFailures = 0
    private var routeServiceDisabled = false
    private var routeQuotaExceeded = false
    private var routeHasEstimate = false
    private var routeHasStaleCache = false
    private var routeHasApproximateRail = false
    private val transferMarkers = mutableListOf<Marker>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapTraceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { (activity as? MainActivity)?.animatePredictiveBack() }

        // 主题色跟随卡片（与统计/交易页一致）：返回、徽章、进度条、播放按钮、选中速度、当前行程行
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            mainAccent = accent.toInt()
            binding.btnBack.setTextColor(mainAccent)
            binding.tvCardBadge.setTextColor(mainAccent)
            binding.cardBadge.background = GradientDrawable().apply {
                cornerRadius = dpToPx(999f)
                setColor(ColorUtils.blendARGB(Palette.SURFACE, mainAccent, 0.12f))
            }
            binding.progressPlayback.background = GradientDrawable().apply {
                cornerRadius = dpToPx(999f)
                setColor(mainAccent)
            }
            binding.btnPlay.backgroundTintList = ColorStateList.valueOf(mainAccent)
            setSpeed(speed)
            if (!model.isEmpty) updateHighlight(scrollList = false)
        }

        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) binding.tvCardBadge.text = "${card.name} · ${card.lastFour}"
        }

        viewModel.currentTripRouteDisplayMode.observe(viewLifecycleOwner) { mode ->
            currentTripRouteDisplayMode = mode
            val event = model.events.getOrNull(currentEventIndex) ?: return@observe
            renderCurrentStation(activeSegmentAt(), event)
        }

        initMap()

        wireControls()

        viewModel.allTransactions.observe(viewLifecycleOwner) { txns ->
            model = JourneyModel.build(txns)
            renderJourney()
        }
    }

    // ── 地图初始化 ──

    private fun initMap() {
        mapView = binding.mapView
        val map = mapView?.map
        tencentMap = map
        // 地图卡片按圆角背景裁切（TextureMapView 才支持）；底图配色由腾讯位置服务控制台的个性化样式决定
        binding.mapCard.clipToOutline = true
        map?.uiSettings?.apply {
            setZoomGesturesEnabled(true)   // 可缩放
            setScrollGesturesEnabled(true)
            setRotateGesturesEnabled(true)
            setZoomControlsEnabled(false)  // 不显示 +/- 按钮
        }
        // zoom≥13 显示站名标签；圆点半径随缩放变化（放越大点越大）。
        // 相机移动中只记录状态，移动结束（onCameraChangeFinished）再一次性重建图标，避免缩放卡顿
        map?.setOnCameraChangeListener(object : TencentMap.OnCameraChangeListener {
            override fun onCameraChange(pos: CameraPosition) {
                val show = pos.zoom >= 13f
                val newRadius = normalRadius(pos.zoom)
                if (show != namesShown || kotlin.math.abs(newRadius - dotRadiusDp) > 0.01f) {
                    namesShown = show
                    dotRadiusDp = newRadius
                    needIconRefresh = true
                }
            }
            override fun onCameraChangeFinished(pos: CameraPosition) {
                if (needIconRefresh) {
                    needIconRefresh = false
                    plainDotCache.clear()
                    highlightDotCache.clear()
                    labelCache.clear()
                    refreshMarkerIcons()
                }
            }
        })
    }

    private fun wireControls() {
        // 播放控件图标用 FontAwesome（fa-play/fa-pause/fa-step-backward/fa-step-forward）
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.otf")
        binding.btnPrev.typeface = fa
        binding.btnPlay.typeface = fa
        binding.btnNext.typeface = fa
        binding.btnPrev.text = ""   // fa-backward-step（上一行程）
        binding.btnPlay.text = if (playing) "" else ""   // fa-pause / fa-play
        binding.btnNext.text = ""   // fa-forward-step（下一行程）

        playRing = PlaybackRingDrawable(resources.displayMetrics.density).also { binding.btnPlay.foreground = it }
        haptics = PlaybackHaptics(requireContext())
        viewModel.playbackHaptics.observe(viewLifecycleOwner) { haptics?.enabled = it }
        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnPrev.setOnClickListener { if (model.events.isNotEmpty()) { pause(); jumpTo(currentEventIndex - 1) } }
        binding.btnNext.setOnClickListener { if (model.events.isNotEmpty()) { pause(); jumpTo(currentEventIndex + 1) } }
        binding.chip05x.setOnClickListener { setSpeed(0.5f) }
        binding.chip1x.setOnClickListener { setSpeed(1f) }
        binding.chip2x.setOnClickListener { setSpeed(2f) }

        // 列表：触摸即暂停，滑动调整当前时间（像歌词那样跟随视口中线）
        binding.tripScroll.setOnTouchListener { _, event ->
            lastListTouchAt = SystemClock.uptimeMillis()
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                scrollAnimator?.cancel()
                pause()
            }
            false
        }
        // 只把"刚被手指拖动/甩动"引起的滚动当作用户选择；列表重建（路线结果到达后）引起的布局滚动
        // 不应改选当前段，否则镜头会莫名飞到别的行程
        binding.tripScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!programmaticScroll && SystemClock.uptimeMillis() - lastListTouchAt < USER_SCROLL_WINDOW_MS) {
                handleUserScroll(scrollY)
            }
        }
    }

    private fun setSpeed(v: Float) {
        speed = v
        fun style(chip: TextView, isSel: Boolean) {
            if (isSel) {
                chip.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(999f)
                    setColor(mainAccent)
                }
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_speed_chip)
                chip.setTextColor(Palette.INK_3)
            }
        }
        style(binding.chip05x, v == 0.5f)
        style(binding.chip1x, v == 1f)
        style(binding.chip2x, v == 2f)
        // 流光速度随播放速度变化：重启当前段流光
        if (shimmerAnimator != null) {
            stopShimmer()
            segmentOverlays.firstOrNull { it.segment === lastActiveSegment }?.let(::startShimmer)
        }
    }

    /** 流光走完一趟的时长（随播放速度缩放，保证总览停留与流光趟数同步） */
    private fun shimmerDurationMs(): Long = (SHIMMER_MS / speed).toLong()

    // ── 渲染 ──

    private fun renderJourney() {
        stopPlayback()
        routeLoadJob?.cancel()
        routeLoadJob = null
        routeGeneration++
        stationMarkers.clear()
        stationMeta.clear()
        stationMarkerKey.clear()
        stationIconKeys.clear()
        stopShimmer()
        segmentOverlays.forEach { holder -> holder.legs.forEach { it.dispose(removeFromMap = false) } }
        segmentOverlays.clear()
        routePlans.clear()
        segmentRows.clear()
        rowFades.values.forEach { it.first.cancel() }
        rowFades.clear()
        lastActiveSegment = null
        highlightedRow = -1
        transferMarkers.clear()
        plainDotCache.clear()
        highlightDotCache.clear()
        labelCache.clear()
        tencentMap?.clear()

        if (model.isEmpty) {
            binding.currentStationRow.removeAllViews()
            binding.currentStationRow.addView(
                TextView(requireContext()).apply {
                    text = "暂无行程数据"
                    setTextColor(Palette.INK)
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )
            binding.tvCurrentTime.text = ""
            bindTripInfo(emptyList())
            return
        }

        // 站点圆点（按站去重；同名且 300m 内的不同站点记录共用一个圆点，避免重叠的两个点颜色不一）
        for (ev in model.events) {
            if (ev.stationId in stationMarkerKey) continue
            val here = LatLng(ev.lat, ev.lng)
            val shared = stationMarkers.entries.firstOrNull { (sid, marker) ->
                stationMeta[sid]?.first == ev.name && distanceMeters(marker.position, here) < SAME_STATION_METERS
            }?.key
            if (shared != null) {
                stationMarkerKey[ev.stationId] = shared
                continue
            }
            stationMarkerKey[ev.stationId] = ev.stationId
            val color = parseColor(ev.lineColor) ?: Palette.INK_3
            stationMeta[ev.stationId] = ev.name to color
            val m = tencentMap?.addMarker(
                MarkerOptions()
                    .position(LatLng(ev.lat, ev.lng))
                    .anchor(0.5f, 0.5f)
                    .level(OverlayLevel.OverlayLevelAboveLabels)
                    .zIndex(STATION_Z.toFloat())
                    .icon(markerDot(color, highlighted = false))
            )
            if (m != null) {
                stationMarkers[ev.stationId] = m
                stationIconKeys[ev.stationId] = "false|${color and 0xFFFFFF}|false|$dotRadiusDp"
            }
        }

        // 网络真实路线返回前先保留示意曲线，失败/离线时可直接降级使用。
        for (seg in model.segments) {
            if (!seg.hasCurve) continue
            val pts = bezierPoints(seg.from, seg.to!!)
            val color = parseColor(seg.lineColor) ?: mainAccent
            val fromColor = parseColor(seg.from.lineColor)
            val toColor = parseColor(seg.to.lineColor)
            val gradientEnds = if (fromColor != null && toColor != null && fromColor != toColor) {
                fromColor to toColor
            } else null
            val colored = tencentMap?.addPolyline(
                PolylineOptions().addAll(pts).level(OverlayLevel.OverlayLevelAboveLabels).zIndex(LINE_Z)
                    .width(dpToPx(GHOST_WIDTH_DP)).color(ghostCurveColor)
            )
            if (colored != null) {
                segmentOverlays.add(
                    SegmentOverlay(
                        segment = seg,
                        legs = mutableListOf(
                            LegOverlay(null, pts, colored, null, color, 5f, gradientEnds = gradientEnds)
                                .apply { shownColors = intArrayOf(ghostCurveColor) }
                        )
                    )
                )
            }
        }

        currentEventIndex = 0
        renderedEventIndex = -1
        renderedSegment = null
        departing = false
        updateHighlight()
        // 起始直接框住第一段行程（不先全景缩到全省）；需地图已完成布局才能按视口尺寸计算缩放
        binding.mapView.post {
            if (_binding == null || model.isEmpty) return@post
            val (center, zoom) = cameraFrameFor(model.events[currentEventIndex])
            tencentMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
        }
        bindTripInfo(model.segments)
        startRouteLoading()

        // 等布局完成后再刷一次进度条宽度（首帧父容器宽度尚未算出）
        view?.post { updateHighlight() }
    }

    // ── 腾讯公交真实路线 ──

    private fun startRouteLoading() {
        val eligible = model.segments.filter(::canLoadTransitRoute)
        routeTotal = eligible.size
        routeFinished = 0
        routeFailures = 0
        routeServiceDisabled = false
        routeQuotaExceeded = false
        routeHasEstimate = false
        routeHasStaleCache = false
        routeHasApproximateRail = false
        updateRouteHint()
        if (eligible.isEmpty()) return

        val generation = routeGeneration
        val current = activeSegmentAt()
        val ordered = buildList {
            current?.takeIf { it in eligible }?.let(::add)
            eligible.firstOrNull { it !== current }?.let(::add)
            eligible.forEach { if (it !in this) add(it) }
        }
        routeLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            // 按当前行程优先顺序逐条解析。缓存命中不会联网；首次额度/权限错误会在
            // Repository 内熔断后续未缓存请求，避免打开一次地图就打满每日额度。
            for (segment in ordered) {
                val state = routeRepository.resolve(routeQuery(segment))
                if (generation != routeGeneration || _binding == null) return@launch
                handleRouteResult(segment, state)
            }
        }
    }

    private fun routeQuery(segment: MapSegment): TransitRouteQuery {
        val to = requireNotNull(segment.to)
        return TransitRouteQuery(
            fromStationId = segment.from.stationId,
            toStationId = to.stationId,
            fromName = segment.from.name,
            toName = to.name,
            fromLineName = segment.from.lineName,
            toLineName = to.lineName,
            fromLat = segment.from.lat,
            fromLng = segment.from.lng,
            toLat = to.lat,
            toLng = to.lng,
            departureTimeSeconds = segment.startTime / 1000L,
            requiredTransitFamily = segment.requiredTransitFamily,
            fromCityName = segment.from.cityName.ifBlank {
                TransitData.resolutionFor(null, segment.from.stationId)?.cityName.orEmpty()
            },
            toCityName = to.cityName.ifBlank {
                TransitData.resolutionFor(null, to.stationId)?.cityName.orEmpty()
            }
        )
    }

    private fun canLoadTransitRoute(segment: MapSegment): Boolean {
        val to = segment.to
        val fromCity = TransitData.resolutionFor(null, segment.from.stationId)?.cityId
        val toCity = to?.let { TransitData.resolutionFor(null, it.stationId)?.cityId }
        return isTransitRouteEligible(segment, fromCity, toCity)
    }

    private fun handleRouteResult(segment: MapSegment, state: RouteLoadState) {
        routeFinished++
        when (state) {
            is RouteLoadState.Ready -> {
                routePlans[segment] = state.plan
                routeHasEstimate = routeHasEstimate || state.plan.estimatedCurrentNetwork
                routeHasStaleCache = routeHasStaleCache || state.plan.stale
                routeHasApproximateRail = routeHasApproximateRail ||
                    state.plan.hasApproximateRailGeometry
                replaceWithRealRoute(segment, state.plan)
                // 路线腿名称/换乘信息到达后刷新行程列表；保持当前段，不触发自动滚动。
                programmaticScroll = true
                try {
                    bindTripInfo(model.segments)
                } finally {
                    programmaticScroll = false
                }
                updateHighlight(scrollList = false)
            }
            is RouteLoadState.Unavailable -> routeFailures++
            is RouteLoadState.Error -> {
                routeFailures++
                if (state.serviceDisabled) routeServiceDisabled = true
                if (state.quotaExceeded) routeQuotaExceeded = true
            }
        }
        updateRouteHint()
    }

    private fun replaceWithRealRoute(segment: MapSegment, plan: RoutePlan) {
        val holder = segmentOverlays.firstOrNull { it.segment === segment } ?: return
        // 当前段的腿被替换：停掉基于旧腿的流光，随后的 updateHighlight 会按新腿重建
        if (holder.segment === lastActiveSegment) stopShimmer()
        holder.legs.forEach { it.dispose() }
        holder.legs.clear()
        for (leg in plan.legs) {
            if (leg.points.size < 2) continue
            val points = leg.points.map { LatLng(it.lat, it.lng) }
            val width = when {
                leg.mode == RouteMode.WALKING -> 2f
                leg.geometryKind == RouteGeometryKind.STATION_SEQUENCE -> 3f
                else -> 5f
            }
            val activeColor = routeLegColor(segment, plan, leg)
            val colored = tencentMap?.addPolyline(
                PolylineOptions()
                    .level(OverlayLevel.OverlayLevelAboveLabels)
                    .zIndex(LINE_Z)
                    .addAll(points)
                    .width(dpToPx(GHOST_WIDTH_DP))
                    .color(ghostCurveColor)
            )
            if (colored != null) {
                holder.legs.add(
                    LegOverlay(
                        white = null,
                        points = points,
                        color = colored,
                        leg = leg,
                        activeColor = activeColor,
                        activeWidthDp = width,
                        geometryKind = leg.geometryKind
                    ).apply { shownColors = intArrayOf(ghostCurveColor) }
                )
            }
        }
        holder.realRoute = holder.legs.isNotEmpty()
    }

    private fun routeLegColor(segment: MapSegment, plan: RoutePlan, leg: RouteLeg): Int {
        if (leg.mode == RouteMode.WALKING) {
            return if (leg.internalTransfer) Palette.INK_3 else 0xFF9AA0A8.toInt()
        }
        parseColor(leg.lineColor)?.let { return it }
        val transit = plan.transitLegs
        val index = transit.indexOf(leg)
        val endpointColor = when (index) {
            0 -> parseColor(segment.from.lineColor)
            transit.lastIndex -> parseColor(segment.to?.lineColor)
            else -> null
        }
        if (endpointColor != null) return endpointColor
        parseColor(TransitData.lineColorOf(segment.from.stationId, leg.title))?.let { return it }
        val palette = intArrayOf(
            0xFF2D7DD2.toInt(), 0xFFE84855.toInt(), 0xFF2A9D8F.toInt(),
            0xFFF4A261.toInt(), 0xFF8E5AC7.toInt(), 0xFF00A6A6.toInt()
        )
        return palette[(leg.title.hashCode() and Int.MAX_VALUE) % palette.size]
    }

    private fun updateRouteHint() {
        if (_binding == null) return
        binding.tvMapHint.text = when {
            routeServiceDisabled -> "路线服务未开通 · 已使用示意线"
            routeQuotaExceeded -> "腾讯路线当日额度已用完 · 未缓存路线使用示意线"
            routeTotal > 0 && routeFinished < routeTotal ->
                "正在加载真实路线 ${routeFinished}/${routeTotal}"
            routeTotal > 0 -> buildList {
                if (routeFailures > 0) add("部分路线使用示意线")
                if (routeHasStaleCache) add("含过期路线缓存")
                if (routeHasEstimate) add("按当前路网推算")
                if (routeHasApproximateRail) add("城际段仅按站点连接")
                if (isEmpty()) add("路线由腾讯地图推算")
                add("可能与实际乘坐不同")
            }.joinToString(" · ")
            else -> "双指缩放 · 滑动下方列表调整时间"
        }
    }

    // ── 播放 ──

    private fun togglePlay() {
        if (model.events.isEmpty()) return
        playing = !playing
        binding.btnPlay.text = if (playing) "" else ""
        if (playing) startPlayback() else pause()
        refreshMarkerIcons()
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        playbackJob = viewLifecycleOwner.lifecycleScope.launch {
            var first = true
            while (isActive) {
                playRoute(startRing = first)
                first = false
            }
        }
    }

    private fun stopPlayback() {
        haptics?.cancel()
        playbackJob?.cancel(); playbackJob = null
        cameraJob?.cancel(); cameraJob = null
        stopRouteClock()
        playing = false
        binding.btnPlay.text = ""
        refreshMarkerIcons()
    }

    private fun pause() {
        haptics?.cancel()
        playbackJob?.cancel(); playbackJob = null
        cameraJob?.cancel(); cameraJob = null
        stopRouteClock()
        if (departing) {
            departing = false
            updateHighlight(scrollList = false)
        }
        playing = false
        binding.btnPlay.text = ""
        refreshMarkerIcons()
    }

    /** 跳到指定事件（手动 prev/next，不沿曲线） */
    private fun jumpTo(index: Int) {
        moveToEvent(index)
    }

    private fun moveToEvent(index: Int) {
        if (model.events.isEmpty()) return
        currentEventIndex = index.coerceIn(0, model.events.lastIndex)
        updateHighlight()
        animateCameraTo(model.events[currentEventIndex])
    }

    /**
     * 播放一段行程，直到切换到下一段：
     * 有出站端的整段：总览停留（使总览恰好覆盖流光走完 [OVERVIEW_SHIMMER_PASSES] 趟）→ 切到出站端 →
     * 飞前停 1s → 镜头飞到下一事件所在整段的总览（await 完成）→ 飞后停 0.5s → 切换。单站事件只有后半程。
     * 进度条按各阶段时长（含预估的飞行时长）连续推进；播放按钮倒计时环只覆盖镜头静止的时间：
     * 镜头到达时满、起飞时空，飞行中隐藏。[startRing] 为 true 时（刚开始播放）由本段自行启动倒计时。
     */
    private suspend fun playRoute(startRing: Boolean) {
        val events = model.events
        val curIdx = currentEventIndex
        events.getOrNull(curIdx) ?: return
        val exitIdx = exitIndexFor(curIdx)
        val leaveIdx = exitIdx ?: curIdx
        val nextIdx = if (leaveIdx >= events.lastIndex) 0 else leaveIdx + 1

        val overviewHoldMs = overviewHoldMs(exitIdx)
        val preFlyMs = (PRE_FLY_MS / speed).toLong()
        val postFlyMs = (POST_FLY_MS / speed).toLong()
        val (center, zoom) = cameraFrameFor(events[nextIdx])
        // 飞行起点：有出站端时为整段总览，否则为当前镜头
        val flyFrom = if (exitIdx != null) cameraFrameFor(events[exitIdx])
        else tencentMap?.cameraPosition?.let { it.target to it.zoom }
        val flyMs = flyFrom?.let { (t, z) -> flyDurationMs(t, z, center, zoom) } ?: 0L
        startRouteClock(
            overviewHoldMs + preFlyMs + flyMs + postFlyMs,
            if (nextIdx > curIdx) eventFraction(nextIdx) else 1f
        )
        if (startRing) startRingClock(overviewHoldMs + preFlyMs)

        if (exitIdx != null) {
            // 同段进站→出站：镜头不动（用户拖动过地图则拉回），只换出站时间
            delay(overviewHoldMs)
            val (c, z) = cameraFrameFor(events[exitIdx])
            flyCameraNow(c, z)
            currentEventIndex = exitIdx
            updateHighlight()
        }
        delay(preFlyMs)
        stopRingClock()
        // 起飞：地图上当前段立即恢复为非激活（卡片/列表仍显示当前段，到达后再切换）
        departing = true
        updateHighlight(scrollList = false)
        haptics?.depart()   // 触感：起飞一下脉冲
        flyCameraNow(center, zoom)
        // 镜头到达：下一段的静止倒计时 = 飞后停 + 下一段总览停留 + 下一段飞前停
        startRingClock(postFlyMs + overviewHoldMs(exitIndexFor(nextIdx)) + preFlyMs)
        delay(postFlyMs)
        currentEventIndex = nextIdx
        departing = false
        if (nextIdx <= curIdx) setProgressWidth(0)   // 循环回到开头
        updateHighlight()
        haptics?.routeShown()   // 新行程高亮出现
    }

    /** 事件是某整段的进站端时，返回该段出站端的事件下标 */
    private fun exitIndexFor(index: Int): Int? {
        val ev = model.events.getOrNull(index) ?: return null
        val to = model.segments.firstOrNull { it.from === ev }?.takeIf { it.hasCurve }?.to ?: return null
        return model.events.indexOfFirst { it === to }.takeIf { it >= 0 }
    }

    /** 整段进站端的额外总览停留（无出站端为 0） */
    private fun overviewHoldMs(exitIdx: Int?): Long = if (exitIdx == null) 0L else
        (TRANSITION_MS + OVERVIEW_SHIMMER_PASSES * shimmerDurationMs() - PRE_FLY_MS / speed).toLong().coerceAtLeast(0L)

    private fun eventFraction(index: Int): Float =
        if (model.events.size <= 1) 1f else index.toFloat() / (model.events.size - 1)

    /** 播放中：进度条在 [totalMs] 内从当前位置匀速推进到 [toFrac] */
    private fun startRouteClock(totalMs: Long, toFrac: Float) {
        routeClock?.cancel()
        progressAnimator?.cancel(); progressAnimator = null
        val bar = binding.progressPlayback
        val totalW = (bar.parent as? View)?.width ?: 0
        val fromW = bar.layoutParams.width.coerceAtLeast(0)
        val toW = (totalW * toFrac.coerceIn(0f, 1f)).toInt()
        routeClock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = totalMs.coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                if (totalW > 0) setProgressWidth(fromW + ((toW - fromW) * f).toInt())
            }
            start()
        }
    }

    private fun stopRouteClock() {
        routeClock?.cancel()
        routeClock = null
        stopRingClock()
    }

    /** 播放按钮倒计时环：[totalMs] 内从满到空（镜头静止期间） */
    private fun startRingClock(totalMs: Long) {
        ringClock?.cancel()
        playRing?.remaining = 1f
        ringClock = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = totalMs.coerceAtLeast(1L)
            interpolator = LinearInterpolator()
            addUpdateListener { playRing?.remaining = it.animatedValue as Float }
            start()
        }
    }

    private fun stopRingClock() {
        ringClock?.cancel()
        ringClock = null
        playRing?.remaining = -1f
    }

    private fun setProgressWidth(w: Int) {
        val bar = binding.progressPlayback
        if (bar.layoutParams.width == w) return
        bar.layoutParams = bar.layoutParams.apply { width = w }
    }

    private fun activeSegmentAt(): MapSegment? {
        val ev = model.events.getOrNull(currentEventIndex) ?: return null
        return model.segments.firstOrNull { it.from === ev || it.to === ev }
    }

    private fun updateHighlight(scrollList: Boolean = true) {
        if (model.events.isEmpty()) return
        val ev = model.events[currentEventIndex]
        val active = activeSegmentAt()

        // 站点圆点：当前站高亮；zoom≥13 显示站名标签
        refreshMarkerIcons()

        // 当前真实路线逐腿着色并加白描边；未加载/失败的段继续使用原贝塞尔降级线。
        // 当前段切换时（新激活 / 刚离开）颜色与线宽做过渡动画，其余段直接定样式。
        val mapActive = if (departing) null else active
        val previous = lastActiveSegment
        lastActiveSegment = mapActive
        for (holder in segmentOverlays) {
            val isActive = mapActive != null && holder.segment === mapActive
            val changed = holder.segment === mapActive && mapActive !== previous ||
                holder.segment === previous && previous !== mapActive
            for (overlay in holder.legs) {
                val ends = overlay.gradientEnds
                val (targetColors, targetWidthDp) = when {
                    isActive && ends != null -> overlay.gradientColors(ends) to overlay.activeWidthDp
                    isActive -> intArrayOf(overlay.displayColor(active = true)) to overlay.activeWidthDp
                    holder.realRoute -> intArrayOf(overlay.displayColor(active = false)) to overlay.activeWidthDp
                    else -> intArrayOf(ghostCurveColor) to GHOST_WIDTH_DP
                }
                overlay.activeColors = if (isActive) targetColors else null
                overlay.showCasing(isActive)
                if (changed) {
                    overlay.animateTo(targetColors, targetWidthDp)
                } else if (overlay.animator?.isRunning != true) {
                    overlay.applyColors(targetColors)
                    overlay.color.setWidth(dpToPx(targetWidthDp))
                }
            }
        }
        if (mapActive !== previous || shimmerAnimator == null) {
            stopShimmer()
            segmentOverlays.firstOrNull { it.segment === mapActive }?.let(::startShimmer)
        }
        renderTransferMarkers(mapActive)

        // 列表：当前行程行高亮（圆角）；播放/跳转时把它滚到视口中间
        val activeIdx = active?.let { model.segments.indexOf(it) } ?: -1
        val highlightColor = ColorUtils.blendARGB(Palette.SURFACE, mainAccent, 0.10f)
        for ((i, row) in segmentRows.withIndex()) {
            val bg = (row.background as? GradientDrawable) ?: GradientDrawable().apply {
                cornerRadius = dpToPx(10f)
                alpha = 0
                row.background = this
            }
            bg.setColor(highlightColor)
            val target = if (i == activeIdx) 255 else 0
            rowFades[row]?.let { (fade, fadeTarget) ->
                if (fade.isRunning && fadeTarget == target) return@let   // 已在渐变到同一目标：保留
                fade.cancel()
                rowFades.remove(row)
            }
            if (rowFades.containsKey(row) || bg.alpha == target) continue
            // 只有当前段真正切换时才渐变；列表重建（路线加载后）直接定状态，避免闪烁
            if (activeIdx != highlightedRow && (i == activeIdx || i == highlightedRow)) {
                rowFades[row] = ObjectAnimator.ofInt(bg, "alpha", bg.alpha, target).setDuration(TRANSITION_MS).apply { start() } to target
            } else {
                bg.alpha = target
            }
        }
        highlightedRow = activeIdx
        if (scrollList) scrollListToCurrent(activeIdx)

        // 文案：站名 + 药丸线路 + 日期/用时（各站时间标在地图站名上）。换行程时整块滑入
        val prevEvent = renderedEventIndex
        val prevSegment = renderedSegment
        renderCurrentStation(active, ev)
        if (prevEvent >= 0 && prevEvent != currentEventIndex && (active == null || active !== prevSegment)) {
            animateStationPanelIn(if (currentEventIndex > prevEvent) 1 else -1)
        }
        renderedEventIndex = currentEventIndex
        renderedSegment = active

        // 播放中进度条由 routeClock 连续推进
        if (!playing) updateProgressBar(eventFraction(currentEventIndex))
    }

    private fun updateProgressBar(frac: Float) {
        val bar = binding.progressPlayback
        val totalW = (bar.parent as? View)?.width ?: 0
        val target = (totalW * frac.coerceIn(0f, 1f)).toInt()
        val from = bar.layoutParams.width
        progressAnimator?.cancel()
        progressAnimator = null
        if (from == target) return
        // 父容器尚未布局时直接定宽，其余平滑过渡
        if (totalW == 0 || from < 0) { setProgressWidth(target); return }
        progressAnimator = ValueAnimator.ofInt(from, target).apply {
            duration = TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { setProgressWidth(it.animatedValue as Int) }
            start()
        }
    }

    /** 播放卡片站名/时间入场：按切换方向轻微横移 + 淡入 */
    private fun animateStationPanelIn(direction: Int) {
        val panel = binding.currentStationPanel
        panel.animate().cancel()
        panel.alpha = 0f
        panel.translationX = direction * dpToPx(16f)
        panel.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(TRANSITION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun renderTransferMarkers(active: MapSegment?) {
        transferMarkers.forEach { it.remove() }
        transferMarkers.clear()
        val plan = active?.let(routePlans::get) ?: return
        val transit = plan.transitLegs
        if (transit.size < 2) return
        for (i in 1 until transit.size) {
            val leg = transit[i]
            val point = leg.points.firstOrNull() ?: continue
            val marker = tencentMap?.addMarker(
                MarkerOptions()
                    .position(LatLng(point.lat, point.lng))
                    .anchor(0.5f, 0.5f)
                    .title("换乘 ${leg.title}")
                    .level(OverlayLevel.OverlayLevelAboveLabels)
                    .zIndex(TRANSFER_Z.toFloat())
                    .icon(transferDot())
            )
            if (marker != null) transferMarkers.add(marker)
        }
    }

    /** 把当前行程行滚动到列表视口中间（播放跟随） */
    private fun scrollListToCurrent(segIndex: Int) {
        if (segIndex !in segmentRows.indices) return
        val row = segmentRows[segIndex]
        val sv = binding.tripScroll
        val maxScroll = ((sv.getChildAt(0)?.height ?: 0) - sv.height).coerceAtLeast(0)
        val target = (row.top - (sv.height - row.height) / 2).coerceIn(0, maxScroll)
        if (sv.scrollY == target) return
        // 平滑滚动：动画期间的滚动回调不当作用户滑动（否则会在途中改选当前段）
        scrollAnimator?.cancel()
        scrollAnimator = ValueAnimator.ofInt(sv.scrollY, target).apply {
            duration = SCROLL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                programmaticScroll = true
                sv.scrollTo(0, it.animatedValue as Int)
                programmaticScroll = false
            }
            start()
        }
    }

    /** 用户滑动列表：视口中线对应的行程行作为当前时间（像歌词滑动） */
    private fun handleUserScroll(scrollY: Int) {
        val sv = binding.tripScroll
        val center = scrollY + sv.height / 2
        var best = -1
        var bestDist = Int.MAX_VALUE
        for ((i, row) in segmentRows.withIndex()) {
            val rc = row.top + row.height / 2
            val d = kotlin.math.abs(rc - center)
            if (d < bestDist) { bestDist = d; best = i }
        }
        if (best >= 0) {
            val cur = activeSegmentAt()?.let { model.segments.indexOf(it) } ?: -1
            if (best != cur) setCurrentSegment(best)
        }
    }

    /** 定位到指定行程行（滑动/点击触发），暂停播放并跳转地图 */
    private fun setCurrentSegment(idx: Int) {
        if (idx !in model.segments.indices) return
        pause()
        val seg = model.segments[idx]
        val eventIdx = model.events.indexOfFirst { it === seg.from }
        if (eventIdx >= 0) {
            currentEventIndex = eventIdx
            updateHighlight(scrollList = false)
            animateCameraTo(model.events[currentEventIndex])
        }
    }

    /** 当前站点行：站名（药丸线路）-> 站名（药丸线路），下面小字为日期与行程用时；各站时间标在地图站名上 */
    private fun renderCurrentStation(active: MapSegment?, ev: MapEvent) {
        val row = binding.currentStationRow
        row.removeAllViews()
        val plan = active?.let(routePlans::get)
        val to = active?.to
        if (active != null && to != null) {
            if (plan != null && shouldShowFullCurrentTripRoute(currentTripRouteDisplayMode, plan.transitLegs.size)) {
                addRouteChain(row, active, plan)
            } else {
                row.addView(stationChip(active.from.name, active.from.lineName, active.from.lineColor))
                row.addView(arrowView())
                row.addView(stationChip(to.name, to.lineName, to.lineColor))
            }
            val minutes = ((to.timeMillis - active.from.timeMillis) / 60_000L).coerceAtLeast(0L)
            binding.tvCurrentTime.text = "${dateText(active.from.timeMillis)} · 用时 ${durationText(minutes)}"
        } else {
            row.addView(stationChip(ev.name, ev.lineName, ev.lineColor))
            binding.tvCurrentTime.text = dateText(ev.timeMillis)
        }
    }

    private fun addRouteChain(container: ViewGroup, segment: MapSegment, plan: RoutePlan) {
        val transit = plan.transitLegs
        if (transit.isEmpty()) return
        val first = transit.first()
        container.addView(
            stationChip(
                first.fromName ?: segment.from.name,
                first.title,
                colorHex(routeLegColor(segment, plan, first))
            )
        )
        for ((index, leg) in transit.withIndex()) {
            container.addView(arrowView())
            val nextLine = transit.getOrNull(index + 1) ?: leg
            val fallbackName = if (index == transit.lastIndex) segment.to?.name.orEmpty() else "换乘"
            container.addView(
                stationChip(
                    leg.toName ?: fallbackName,
                    nextLine.title,
                    colorHex(routeLegColor(segment, plan, nextLine))
                )
            )
        }
    }

    /** 站点时间：HH:mm；与 [sameDayAs] 不在同一天时带上月-日 */
    private fun stopTime(millis: Long, sameDayAs: Long?): String {
        val pattern = if (sameDayAs != null && !sameDay(millis, sameDayAs)) "MM-dd HH:mm" else "HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return fmt.format(Date(a)) == fmt.format(Date(b))
    }

    private fun dateText(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd EEE", Locale.getDefault()).format(Date(millis))

    private fun durationText(minutes: Long): String =
        if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分钟"

    private fun colorHex(color: Int): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

    /** 站名 + 线路药丸 的组合视图 */
    private fun stationChip(name: String, lineName: String, lineColor: String?): RouteStationChipLayout {
        val container = RouteStationChipLayout(requireContext())
        val nameTv = TextView(requireContext()).apply {
            text = name
            textSize = 14f
            setTextColor(Palette.INK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setSingleLine(false)
            setPadding(0, 0, dpToPx(4f).toInt(), 0)
        }
        container.addView(nameTv)
        if (lineName.isNotBlank()) {
            container.addView(requireContext().linePill(lineName, lineColor))
        }
        return container
    }

    private fun arrowView(): TextView = TextView(requireContext()).apply {
        text = "→"
        tag = RouteFlowLayout.KEEP_WITH_NEXT_TAG
        setTextColor(Palette.INK_3)
        textSize = 14f
        setPadding(dpToPx(4f).toInt(), 0, dpToPx(4f).toInt(), 0)
    }

    /** 手动/滑动调用：异步 flyTo（launch cameraJob），镜头框住该事件所在的整段行程 */
    private fun animateCameraTo(ev: MapEvent) {
        val map = tencentMap ?: return
        val (center, zoom) = cameraFrameFor(ev)
        if (map.cameraPosition == null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
            return
        }
        cameraJob?.cancel()
        cameraJob = viewLifecycleOwner.lifecycleScope.launch {
            flyToNow(map, center, zoom)
        }
    }

    /** 播放调用：同步 flyTo，await 完成（用于飞前/飞后停时的节奏控制） */
    private suspend fun animateCameraToNow(ev: MapEvent) {
        val map = tencentMap ?: return
        val (center, zoom) = cameraFrameFor(ev)
        if (map.cameraPosition == null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
            return
        }
        cameraJob?.cancel()
        flyToNow(map, center, zoom, speedFactor = speed)
    }

    /** 播放用：飞到指定镜头并等待完成；已在目标附近则跳过（避免原地空转一整段时长） */
    private suspend fun flyCameraNow(target: LatLng, zoom: Float) {
        val map = tencentMap ?: return
        val cp = map.cameraPosition
        if (cp == null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
            return
        }
        cameraJob?.cancel()
        if (kotlin.math.abs(cp.zoom - zoom) < 0.05f && distanceMeters(cp.target, target) < 5.0) return
        flyToNow(map, target, zoom, speedFactor = speed)
    }

    /**
     * 事件对应的镜头：所在行程段有线路时框住整段（真实路线或示意曲线的全部点），留出边距；
     * 单站事件（无行程段）以站点为中心、街区级缩放。
     */
    private fun cameraFrameFor(ev: MapEvent): Pair<LatLng, Float> {
        val segment = model.segments.firstOrNull { (it.from === ev || it.to === ev) && it.hasCurve }
        val points = segment?.let { seg ->
            segmentOverlays.firstOrNull { it.segment === seg }?.legs?.flatMap { it.points }
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(LatLng(seg.from.lat, seg.from.lng), seg.to?.let { LatLng(it.lat, it.lng) })
        }
        if (points == null || points.size < 2) return LatLng(ev.lat, ev.lng) to STATION_ZOOM
        return fitFrame(points)
    }

    /** 按地图视口（逻辑像素）计算能完整容纳 [points] 的中心与缩放；上方让出提示条，四周留白 */
    private fun fitFrame(points: List<LatLng>): Pair<LatLng, Float> {
        val density = resources.displayMetrics.density
        val viewW = binding.mapView.width / density
        val viewH = binding.mapView.height / density
        val xy = points.map { worldXY(it.longitude, it.latitude, 256.0) }
        val minX = xy.minOf { it.first }; val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }; val maxY = xy.maxOf { it.second }
        val midX = (minX + maxX) / 2.0
        val midY = (minY + maxY) / 2.0
        if (viewW <= 0f || viewH <= 0f) {
            val (lng, lat) = worldToLatLng(midX, midY, 256.0)
            return LatLng(lat, lng) to FALLBACK_FRAME_ZOOM
        }
        val availW = (viewW - FRAME_PAD_X_DP * 2f).coerceAtLeast(40f)
        val availH = (viewH - FRAME_PAD_TOP_DP - FRAME_PAD_BOTTOM_DP).coerceAtLeast(40f)
        val spanX = (maxX - minX).coerceAtLeast(1e-9)
        val spanY = (maxY - minY).coerceAtLeast(1e-9)
        val zoom = minOf(
            Math.log(availW / spanX) / Math.log(2.0),
            Math.log(availH / spanY) / Math.log(2.0)
        ).toFloat().coerceIn(MIN_FRAME_ZOOM, MAX_FRAME_ZOOM)
        // 上下留白不对称：把视觉中心下移半个差值，使路线落在可视区域正中
        val scale = Math.pow(2.0, zoom.toDouble())
        val centerY = midY - (FRAME_PAD_TOP_DP - FRAME_PAD_BOTTOM_DP) / 2.0 / scale
        val (lng, lat) = worldToLatLng(midX, centerY, 256.0)
        return LatLng(lat, lng) to zoom
    }

    /** 播放预估：从镜头 ([start], [startZoom]) 飞到目标的时长；与 [flyCameraNow] 一样，已在目标附近视为不飞 */
    private fun flyDurationMs(start: LatLng, startZoom: Float, target: LatLng, targetZoom: Float): Long {
        if (kotlin.math.abs(startZoom - targetZoom) < 0.05f && distanceMeters(start, target) < 5.0) return 0L
        return flyPath(start, startZoom, target, targetZoom, speed).durationMs.toLong()
    }

    private class FlyPath(
        val startZoom: Float,
        val startWorldSize: Double,
        val from: Pair<Double, Double>,
        val dx: Double,
        val dy: Double,
        val u1: Double,
        val w0: Double,
        val rho: Double,
        val rho2: Double,
        val r0: Double,
        val s: Double,
        val pureZoom: Boolean,
        val durationMs: Double
    )

    private fun cosh(n: Double) = (Math.exp(n) + Math.exp(-n)) / 2.0
    private fun sinh(n: Double) = (Math.exp(n) - Math.exp(-n)) / 2.0
    private fun tanh(n: Double) = sinh(n) / cosh(n)

    /** Van Wijk 最优路径参数与时长（[flyToNow] 与播放预估共用） */
    private fun flyPath(start: LatLng, startZoom: Float, target: LatLng, targetZoom: Float, speedFactor: Float): FlyPath {
        val startWorldSize = 256.0 * Math.pow(2.0, startZoom.toDouble())
        val from = worldXY(start.longitude, start.latitude, startWorldSize)
        val to = worldXY(target.longitude, target.latitude, startWorldSize)
        val dx = to.first - from.first
        val dy = to.second - from.second
        val u1 = Math.hypot(dx, dy)                      // 路径像素长度

        // —— Van Wijk 最优路径参数（与 MapLibre camera.ts flyTo / camera_helper.ts handleFlyTo 一致）——
        val viewW = binding.mapView.width.toFloat().coerceAtLeast(1f)
        val viewH = binding.mapView.height.toFloat().coerceAtLeast(1f)
        // w0 须与 worldXY 的 worldSize 同单位（逻辑像素=设备像素/density），否则 centerFactor 会 >1 飞过目标
        val w0 = (maxOf(viewW, viewH) / resources.displayMetrics.density).toDouble()
        val scaleOfZoom = Math.pow(2.0, (targetZoom - startZoom).toDouble())
        val w1 = w0 / scaleOfZoom
        val minZoom = minOf(3.0, startZoom.toDouble(), targetZoom.toDouble())
        val scaleOfMinZoom = Math.pow(2.0, minZoom - startZoom.toDouble())
        val wMax = w0 / scaleOfMinZoom

        val rho = minOf(1.42, Math.sqrt(wMax / u1 * 2.0))
        val rho2 = rho * rho

        fun zoomOutFactor(descent: Boolean): Double {
            val b = (w1 * w1 - w0 * w0 + (if (descent) -1.0 else 1.0) * rho2 * rho2 * u1 * u1) /
                (2.0 * (if (descent) w1 else w0) * rho2 * u1)
            return Math.log(Math.sqrt(b * b + 1.0) - b)
        }

        val r0 = zoomOutFactor(false)
        var S = (zoomOutFactor(true) - r0) / rho         // 总路径长度
        // 原版退化分支：路径几乎为零（同站）或 S 无界 → 退化为纯缩放（不平移）
        val pureZoom = Math.abs(u1) < 0.000002 || !S.isFinite()
        if (pureZoom) {
            S = Math.abs(Math.log(w1 / w0)) / rho
        }
        // 时长与 MapLibre 一致按路径长度定：S / 速度（屏/秒），远距离飞得久、近距离快，避免中段过快
        val durationMs = (S / FLY_SCREENS_PER_SEC * 1000.0 / speedFactor)
            .coerceIn(FLY_MIN_MS / speedFactor.toDouble(), FLY_MAX_MS.toDouble())
        return FlyPath(startZoom, startWorldSize, from, dx, dy, u1, w0, rho, rho2, r0, S, pureZoom, durationMs)
    }

    /** 原版 MaptileGL/MapLibre flyTo：Van Wijk「smooth and efficient zooming and panning」最优路径。
     *  镜头按最优曲线先拉远（zoom 下降）再拉近（zoom 回升），路径与缩放幅度随距离自适应。
     *  suspend：播放流程里 await 完成（飞后停 2s 再切换）。 */
    private suspend fun flyToNow(map: TencentMap, target: LatLng, targetZoom: Float, speedFactor: Float = 1f) {
        val cp = map.cameraPosition ?: return
        val p = flyPath(cp.target, cp.zoom, target, targetZoom, speedFactor)

        // 按显示帧推进；进度用"加速-匀速-减速"曲线：只在首尾 25% 缓入缓出，中段匀速（峰速仅为均速 1.33 倍）
        val startNanos = awaitFrame()
        while (currentCoroutineContext().isActive) {
            val frameNanos = awaitFrame()
            val t = ((frameNanos - startNanos) / 1_000_000.0 / p.durationMs).coerceAtMost(1.0)
            if (t >= 1.0) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, targetZoom))
                break
            }
            val k = cruiseEasing(t)
            val s = k * p.s
            // 与 MapLibre 原版一致：不加任何防御 clamp
            val w = if (p.pureZoom) Math.exp(k * p.rho * p.s) else cosh(p.r0) / cosh(p.r0 + p.rho * s)
            val centerFactor = if (p.pureZoom) 0.0
            else p.w0 * (cosh(p.r0) * tanh(p.r0 + p.rho * s) - sinh(p.r0)) / p.rho2 / p.u1
            val zoom = (p.startZoom + scaleZoom(1.0 / w)).toFloat().coerceIn(3f, 21f)
            val (lng, lat) = worldToLatLng(p.from.first + p.dx * centerFactor, p.from.second + p.dy * centerFactor, p.startWorldSize)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
        }
    }

    /** 梯形速度曲线：[0, a] 匀加速、[a, 1-a] 匀速、[1-a, 1] 匀减速；连续且端点速度为 0 */
    private fun cruiseEasing(t: Double, a: Double = 0.25): Double {
        val vMax = 1.0 / (1.0 - a)
        return when {
            t <= 0.0 -> 0.0
            t < a -> 0.5 * vMax * t * t / a
            t < 1.0 - a -> vMax * (t - a / 2.0)
            t < 1.0 -> 1.0 - 0.5 * vMax * (1.0 - t) * (1.0 - t) / a
            else -> 1.0
        }
    }

    /** WGS 墨卡托世界坐标投影（像素，给定世界尺寸） */
    private fun worldXY(lng: Double, lat: Double, worldSize: Double): Pair<Double, Double> {
        val x = worldSize * (lng / 360.0 + 0.5)
        val latRad = lat * Math.PI / 180.0
        val y = worldSize * (0.5 - Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0)) / (2.0 * Math.PI))
        return x to y
    }

    private fun worldToLatLng(x: Double, y: Double, worldSize: Double): Pair<Double, Double> {
        val lng = (x / worldSize - 0.5) * 360.0
        // 反墨卡托：φ = π/2 - 2·atan(e^(2π(y/worldSize - 0.5)))；之前漏了 -π/2，纬度整体 +90°
        val yFrac = y / worldSize
        val lat = Math.PI / 2.0 - 2.0 * Math.atan(Math.exp(2.0 * Math.PI * (yFrac - 0.5)))
        return lng to lat * 180.0 / Math.PI
    }

    /** 球面距离（haversine，米） */
    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = (b.latitude - a.latitude) * Math.PI / 180.0
        val dLng = (b.longitude - a.longitude) * Math.PI / 180.0
        val s = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0) +
            Math.cos(a.latitude * Math.PI / 180.0) * Math.cos(b.latitude * Math.PI / 180.0) *
            Math.sin(dLng / 2.0) * Math.sin(dLng / 2.0)
        return 2.0 * r * Math.asin(Math.sqrt(s))
    }

    /** scale → zoom（log2） */
    private fun scaleZoom(scale: Double): Double = Math.log(scale) / Math.log(2.0)

    // ── 贝塞尔曲线（二次） ──

    private fun bezierPoints(a: MapEvent, b: MapEvent, samples: Int = 30): List<LatLng> {
        val dx = b.lng - a.lng
        val dy = b.lat - a.lat
        val len = kotlin.math.hypot(dx, dy)
        val off = len * 0.22
        val mx = (a.lng + b.lng) / 2
        val my = (a.lat + b.lat) / 2
        val ux = if (len > 1e-6) -dy / len else 0.0
        val uy = if (len > 1e-6) dx / len else 1.0
        val cLng = mx + ux * off
        val cLat = my + uy * off
        val out = ArrayList<LatLng>(samples + 1)
        for (i in 0..samples) {
            val t = i.toDouble() / samples
            val mt = 1 - t
            val lat = mt * mt * a.lat + 2 * mt * t * cLat + t * t * b.lat
            val lng = mt * mt * a.lng + 2 * mt * t * cLng + t * t * b.lng
            out.add(LatLng(lat, lng))
        }
        return out
    }

    // ── 站点标记图标 ──

    /** 普通圆点半径（dp）：随缩放变化，低 zoom 点很小，高 zoom 点大 */
    private fun normalRadius(zoom: Float): Float = (1.0f + (zoom - 3f) * 0.3f).coerceIn(1.0f, 6f)

    /** 普通/高亮圆点（半径随当前 zoom） */
    private fun markerDot(color: Int, highlighted: Boolean): BitmapDescriptor {
        val r = if (highlighted) dotRadiusDp * 1.6f else dotRadiusDp
        if (highlighted) return highlightDotCache.getOrPut(color) { stationDot(color, r, true) }
        return plainDotCache.getOrPut(color) { stationDot(color, r, false) }
    }

    /** 按当前 zoom 与选中态刷新全部站点标记：当前段两端始终带站名；其余站点 zoom≥13 且非播放时带站名，否则纯圆点。
     *  白描边集合 = 当前站 + 当前行程段的两端（选中段时两边都高亮描边）。 */
    private fun refreshMarkerIcons() {
        // 起飞前往下一段时不再高亮当前段两端
        val currentEvent = if (departing) null else model.events.getOrNull(currentEventIndex)
        val active = if (departing) null else activeSegmentAt()
        // 高亮站点 → 本条记录里的线路色（换乘站被多条线共用，默认圆点色只取了首次出现的线路）
        val recordColors = mutableMapOf<Long, Int?>()
        fun key(stationId: Long) = stationMarkerKey[stationId] ?: stationId
        currentEvent?.let { recordColors[key(it.stationId)] = parseColor(it.lineColor) }
        if (active != null && active.hasCurve) {
            recordColors[key(active.from.stationId)] = parseColor(active.from.lineColor)
            active.to?.let { recordColors[key(it.stationId)] = parseColor(it.lineColor) }
        }
        // 当前段两端站名后标出刷卡时间（进站/出站），当前事件的时间用主题色
        val stopTimes = mutableMapOf<Long, Pair<String, Boolean>>()
        val to = active?.to
        if (active != null && to != null) {
            stopTimes[key(active.from.stationId)] = stopTime(active.from.timeMillis, null) to (currentEvent !== to)
            stopTimes[key(to.stationId)] = stopTime(to.timeMillis, active.from.timeMillis) to (currentEvent === to)
        } else if (currentEvent != null) {
            stopTimes[key(currentEvent.stationId)] = stopTime(currentEvent.timeMillis, null) to true
        }
        val outlined = recordColors.keys
        for (sid in stationMarkers.keys.toList()) {
            val marker = stationMarkers[sid] ?: continue
            val meta = stationMeta[sid] ?: continue
            val name = meta.first
            val color = recordColors[sid] ?: meta.second
            val highlighted = sid in outlined
            // 当前段两端（及当前站）始终显示站名；其余站点仅在 zoom≥13 且未在播放时显示
            val labeled = (namesShown && !playing) || highlighted
            val time = stopTimes[sid]
            val timeKey = time?.let { "${it.first}|${it.second}|${mainAccent and 0xFFFFFF}" }
            val iconKey = "$labeled|${color and 0xFFFFFF}|$highlighted|$dotRadiusDp|$timeKey"
            if (stationIconKeys[sid] == iconKey) continue
            stationIconKeys[sid] = iconKey
            val icon = if (labeled) {
                labelCache.getOrPut("$name|${color and 0xFFFFFF}|$highlighted|$timeKey") {
                    labeledDot(color, name, highlighted, time?.first, time?.second == true)
                }
            } else markerDot(color, highlighted)
            // 腾讯 SDK 的 Marker.setIcon 在新旧图标尺寸相同时不会重绘（换线路色后仍显示旧颜色），
            // 所以图标变化时直接换一个新标记；当前段两端压在线路之上，其余站点在当前段线路之下
            val position = marker.position
            marker.remove()
            tencentMap?.addMarker(
                MarkerOptions()
                    .position(position)
                    .anchor(0.5f, if (labeled) labelAnchorV(highlighted) else 0.5f)
                    .level(OverlayLevel.OverlayLevelAboveLabels)
                    .zIndex((if (highlighted) ACTIVE_STATION_Z else STATION_Z).toFloat())
                    .icon(icon)
            )?.let { stationMarkers[sid] = it }
        }
    }

    // ── 覆盖物绘制（与界面一致：白描边圆点、白色胶囊标签 + 柔和阴影） ──

    private fun ringWidthDp(highlighted: Boolean) = if (highlighted) 2.5f else 1.2f

    private fun shadowPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
        val d = resources.displayMetrics.density
        setShadowLayer(2f * d, 0f, 0.5f * d, 0x33000000)
    }

    /** 线路色圆点 + 白描边（任意底图上都清晰）；高亮时描边更粗并带阴影 */
    private fun drawDot(canvas: Canvas, cx: Float, cy: Float, fill: Int, radiusDp: Float, highlighted: Boolean) {
        val d = resources.displayMetrics.density
        val ring = ringWidthDp(highlighted) * d
        val r = radiusDp * d
        val outer = if (highlighted) shadowPaint(Color.WHITE) else Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(cx, cy, r + ring, outer)
        canvas.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
    }

    /** 圆点在上、站名白色胶囊标签在下的图标；anchor 垂直比例见 [labelAnchorV] */
    /** 圆点 + 站名标签；[time] 非空时标签为「站名 时间」，[timeCurrent] 的时间用主题色 */
    private fun labeledDot(
        color: Int,
        name: String,
        highlighted: Boolean,
        time: String? = null,
        timeCurrent: Boolean = false
    ): BitmapDescriptor {
        val d = resources.displayMetrics.density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_TEXT_DP * d
            this.color = if (highlighted) Palette.INK else Palette.INK_2
            typeface = if (highlighted) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_TEXT_DP * d
            this.color = if (timeCurrent) mainAccent else Palette.INK_3
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val nameW = textPaint.measureText(name)
        val timeGap = LABEL_TIME_GAP_DP * d
        val textW = nameW + (time?.let { timeGap + timePaint.measureText(it) } ?: 0f)
        val pad = MARKER_PAD_DP * d
        val dotR = if (highlighted) dotRadiusDp * 1.6f else dotRadiusDp
        val dotOuter = (dotR + ringWidthDp(highlighted)) * d
        val labelW = textW + LABEL_PAD_X_DP * 2f * d
        val labelH = (LABEL_TEXT_DP + LABEL_PAD_Y_DP * 2f) * d
        val width = maxOf(dotOuter * 2f, labelW) + pad * 2f
        val height = pad + dotOuter * 2f + LABEL_GAP_DP * d + labelH + pad
        val bmp = Bitmap.createBitmap(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val cx = width / 2f
        drawDot(canvas, cx, pad + dotOuter, color, dotR, highlighted)

        val labelLeft = (width - labelW) / 2f
        val labelTop = pad + dotOuter * 2f + LABEL_GAP_DP * d
        canvas.drawRoundRect(
            labelLeft, labelTop, labelLeft + labelW, labelTop + labelH,
            labelH / 2f, labelH / 2f, shadowPaint(Color.WHITE)
        )
        val fm = textPaint.fontMetrics
        val baseline = labelTop + labelH / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(name, labelLeft + LABEL_PAD_X_DP * d, baseline, textPaint)
        if (time != null) canvas.drawText(time, labelLeft + LABEL_PAD_X_DP * d + nameW + timeGap, baseline, timePaint)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 带标签图标的锚点：让圆点中心对准坐标（与 [labeledDot] 的尺寸计算一致，单位 dp） */
    private fun labelAnchorV(highlighted: Boolean): Float {
        val dotOuter = (if (highlighted) dotRadiusDp * 1.6f else dotRadiusDp) + ringWidthDp(highlighted)
        val height = MARKER_PAD_DP * 2f + dotOuter * 2f + LABEL_GAP_DP + LABEL_TEXT_DP + LABEL_PAD_Y_DP * 2f
        return (MARKER_PAD_DP + dotOuter) / height
    }

    private fun stationDot(fill: Int, radiusDp: Float, ring: Boolean): BitmapDescriptor {
        val d = resources.displayMetrics.density
        val size = ((radiusDp + ringWidthDp(ring) + MARKER_PAD_DP) * 2f * d).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawDot(Canvas(bmp), size / 2f, size / 2f, fill, radiusDp, ring)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 换乘点：白底 + 深灰描边（地铁图换乘站样式），不与线路色抢视觉 */
    private fun transferDot(): BitmapDescriptor {
        val d = resources.displayMetrics.density
        val r = 4.5f * d
        val stroke = 2f * d
        val size = ((r + stroke) * 2f + MARKER_PAD_DP * 2f * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val c = size / 2f
        canvas.drawCircle(c, c, r + stroke, shadowPaint(Palette.INK_2))
        canvas.drawCircle(c, c, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun parseColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        val value = hex.trim()
        val normalized = if (value.matches(Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}"))) {
            "#$value"
        } else {
            value
        }
        return try { Color.parseColor(normalized) } catch (e: Exception) { null }
    }

    /** 线路颜色/线宽过渡（当前段切换时）；白描边宽度随线宽同步变化 */
    private fun LegOverlay.animateTo(targetColors: IntArray, targetWidthDp: Float) {
        animator?.cancel()
        val fromColors = shownColors
        val fromWidth = color.width
        val toWidth = dpToPx(targetWidthDp)
        // 单色 ↔ 渐变之间过渡时按逐点颜色插值；两端都是单色时只插 1 个颜色
        val n = maxOf(fromColors.size, targetColors.size)
        fun at(colors: IntArray, i: Int) = if (colors.size == 1) colors[0] else colors[i]
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TRANSITION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                if (removed) return@addUpdateListener
                val f = it.animatedValue as Float
                val w = fromWidth + (toWidth - fromWidth) * f
                applyColors(IntArray(n) { i -> ColorUtils.blendARGB(at(fromColors, i), at(targetColors, i), f) })
                color.setWidth(w)
                white?.setWidth(w + dpToPx(CASING_EXTRA_DP) * f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 结束时落到精确目标（单色目标回到单色，关闭渐变）
                    applyColors(targetColors)
                }
            })
            start()
        }
    }

    /**
     * 流光：一段柔和的高光沿当前段从起点流向终点，循环播放以指示方向。
     * 不改线路本身的颜色（逐点 setColors 在真实路线点串上会让地图引擎原生崩溃），而是在当前段线路之上
     * 叠 [SHIMMER_LAYERS] 层纯色短折线：外层长而淡、内层短而亮，叠出中心最亮、两侧渐回线路色的光带。
     * 光带跨过换乘（不同线路色的腿）时每层在腿的分界处断开，各段分别取所在腿的颜色。
     * 不用渐变折线（gradient + 逐点颜色）：腾讯引擎每帧更新渐变时，光带最前一小段偶发整段渲染成黑色。
     * 多条腿按距离视为一条路径；各层直接取路径上的真实拐点，急弯处也贴合线路。
     */
    private fun startShimmer(holder: SegmentOverlay) {
        val path = ShimmerPath.of(holder.legs) ?: return
        var lastFraction = -1f
        shimmerAnimator = ValueAnimator.ofFloat(-SHIMMER_HALF, 1f + SHIMMER_HALF).apply {
            duration = shimmerDurationMs()
            startDelay = TRANSITION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                if (holder.legs.any { it.removed }) return@addUpdateListener
                // 每趟流光开始（首帧或进度回绕）时，播放中给一次随流光渐弱的振动
                val fraction = va.animatedFraction
                if ((lastFraction < 0f || fraction < lastFraction) && playing && !departing) {
                    haptics?.highlightSweep(va.duration)
                }
                lastFraction = fraction
                val center = va.animatedValue as Float
                drawShimmerLayers(path, center)
            }
            start()
        }
    }

    /**
     * 第 j 层覆盖 [center ± SHIMMER_HALF·(L−j)/L]（夹在路径内），按腿切段；每段颜色为该段线路色向白色混合
     * SHIMMER_STRENGTH·(1−d)²（d 取该层半径的中点比例），层越靠内越亮、zIndex 越高。
     * 段数变化时复用已有折线、多余的移除（腾讯折线不认 visible）。
     */
    private fun drawShimmerLayers(path: ShimmerPath, center: Float) {
        for (j in 0 until SHIMMER_LAYERS) {
            val radius = SHIMMER_HALF * (SHIMMER_LAYERS - j) / SHIMMER_LAYERS
            val from = (center - radius).coerceIn(0f, 1f)
            val to = (center + radius).coerceIn(0f, 1f)
            val pieces = if (to - from < 0.002f) emptyList() else path.pieces(from.toDouble(), to.toDouble())
            val d = (SHIMMER_LAYERS - j - 0.5f) / SHIMMER_LAYERS
            val strength = SHIMMER_STRENGTH * (1f - d) * (1f - d)
            val lines = shimmerLayers[j]
            for ((i, piece) in pieces.withIndex()) {
                val color = ColorUtils.blendARGB(piece.color, Color.WHITE, strength)
                val widthPx = dpToPx(piece.widthDp)
                val line = lines.getOrNull(i)
                if (line == null) {
                    tencentMap?.addPolyline(
                        PolylineOptions()
                            .level(OverlayLevel.OverlayLevelAboveLabels)
                            .zIndex(SHIMMER_Z + j)
                            .addAll(piece.points)
                            .width(widthPx)
                            .color(color)
                    )?.let(lines::add) ?: return
                } else {
                    line.setPoints(piece.points)
                    line.setColor(color)
                    line.setWidth(widthPx)
                }
            }
            while (lines.size > pieces.size) lines.removeAt(lines.lastIndex).remove()
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        for (lines in shimmerLayers) {
            lines.forEach { it.remove() }
            lines.clear()
        }
    }

    /**
     * 当前段整条路径（各腿首尾相接）：按累计距离截取子路径。
     * 点 k 的颜色/线宽属于它所在的腿（腿间相接的共用点归前一条腿）；线段 k→k+1 归点 k+1 的腿。
     */
    private class ShimmerPath(
        private val points: List<LatLng>,
        private val dist: DoubleArray,
        private val colors: IntArray,
        private val widths: FloatArray,
        private val legOf: IntArray
    ) {
        private val total = dist.last()

        /** 光带的一段：同一条腿上的子路径及其线路色、线宽 */
        class Piece(val points: List<LatLng>, val color: Int, val widthDp: Float)

        private fun indexAt(d: Double): Int {
            var lo = 0
            var hi = dist.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (dist[mid] <= d) lo = mid else hi = mid
            }
            return lo
        }

        private fun pointAtDistance(d: Double): LatLng {
            val i = indexAt(d)
            val j = (i + 1).coerceAtMost(points.size - 1)
            val span = dist[j] - dist[i]
            val t = if (span <= 0.0) 0.0 else ((d - dist[i]) / span).coerceIn(0.0, 1.0)
            val a = points[i]; val b = points[j]
            return LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
        }

        /**
         * [from, to]（占全长比例）之间的子路径，在腿的分界处切开：每段为两端插值点 + 中间的原始拐点
         * （距离严格递增，不含重复点），颜色/线宽取该段中点所在线段。
         */
        fun pieces(from: Double, to: Double): List<Piece> {
            val d0 = from.coerceIn(0.0, 1.0) * total
            val d1 = to.coerceIn(0.0, 1.0) * total
            if (d1 <= d0) return emptyList()
            val lastSeg = points.size - 2
            val s0 = indexAt(d0).coerceAtMost(lastSeg)
            val e = indexAt(d1)
            val s1 = (if (e > 0 && dist[e] >= d1) e - 1 else e).coerceIn(s0, lastSeg)
            val out = ArrayList<Piece>()
            var gs = s0
            while (gs <= s1) {
                var ge = gs
                while (ge < s1 && legOf[ge + 2] == legOf[gs + 1]) ge++
                val start = maxOf(d0, dist[gs])
                val end = minOf(d1, dist[ge + 1])
                if (end > start) {
                    val pts = ArrayList<LatLng>()
                    pts.add(pointAtDistance(start))
                    for (k in gs + 1..ge) if (dist[k] > start && dist[k] < end) pts.add(points[k])
                    pts.add(pointAtDistance(end))
                    val seg = indexAt((start + end) / 2.0).coerceIn(gs, ge)
                    out.add(Piece(pts, colors[seg + 1], widths[seg + 1]))
                }
                gs = ge + 1
            }
            return out
        }

        companion object {
            /** 用当前段各腿的点与基础颜色建路径；相邻重复点（腿首尾相接处）去掉 */
            fun of(legs: List<LegOverlay>): ShimmerPath? {
                val pts = ArrayList<LatLng>()
                val cols = ArrayList<Int>()
                val ws = ArrayList<Float>()
                val owners = ArrayList<Int>()
                for ((legIndex, leg) in legs.withIndex()) {
                    val base = leg.activeColors ?: continue
                    for ((i, p) in leg.points.withIndex()) {
                        val last = pts.lastOrNull()
                        if (last != null && last.latitude == p.latitude && last.longitude == p.longitude) continue
                        pts.add(p)
                        cols.add(if (base.size == 1) base[0] else base[i.coerceAtMost(base.size - 1)])
                        ws.add(leg.activeWidthDp)
                        owners.add(legIndex)
                    }
                }
                if (pts.size < 2) return null
                val dist = DoubleArray(pts.size)
                for (k in 1 until pts.size) dist[k] = dist[k - 1] + approxMeters(pts[k - 1], pts[k])
                if (dist.last() <= 0.0) return null
                return ShimmerPath(pts, dist, cols.toIntArray(), ws.toFloatArray(), owners.toIntArray())
            }

            private fun approxMeters(a: LatLng, b: LatLng): Double {
                val lat = Math.toRadians((a.latitude + b.latitude) / 2.0)
                val dx = Math.toRadians(b.longitude - a.longitude) * Math.cos(lat)
                val dy = Math.toRadians(b.latitude - a.latitude)
                return Math.hypot(dx, dy) * 6_371_000.0
            }
        }
    }

    /** 逐点渐变色：起点线路色 → 终点线路色 */
    private fun LegOverlay.gradientColors(ends: Pair<Int, Int>): IntArray {
        val last = (points.size - 1).coerceAtLeast(1)
        return IntArray(points.size) { i -> ColorUtils.blendARGB(ends.first, ends.second, i.toFloat() / last) }
    }

    /** 移除一条腿的覆盖物：先标记已移除再取消动画（cancel 会回调 onAnimationEnd 再次着色） */
    private fun LegOverlay.dispose(removeFromMap: Boolean = true) {
        removed = true
        animator?.cancel()
        animator = null
        if (removeFromMap) {
            white?.remove()
            color.remove()
        }
        white = null
    }

    /** 应用颜色：1 个颜色为单色线；多个颜色为逐点渐变（与 points 一一对应，仅限示意曲线） */
    private fun LegOverlay.applyColors(colors: IntArray) {
        if (removed) return
        if (colors.size > 1 && (leg != null || colors.size != points.size)) {
            // 真实路线腿不做逐点颜色（见流光处说明），退化为首色单色
            color.setGradientEnable(false)
            color.setColor(colors[0])
            shownColors = intArrayOf(colors[0])
            return
        }
        if (colors.size == 1) {
            color.setGradientEnable(false)
            color.setColor(colors[0])
        } else {
            color.setGradientEnable(true)
            color.setColors(colors, IntArray(colors.size) { it })
        }
        shownColors = colors
    }

    /** 白色描边：只给当前段按需创建（置于线路之下），离开即移除；见 [LegOverlay] 说明 */
    private fun LegOverlay.showCasing(shown: Boolean) {
        if (removed) return
        color.setZIndex(if (shown) ACTIVE_LINE_Z else LINE_Z)
        if (!shown) {
            white?.remove()
            white = null
            return
        }
        if (white != null) return
        white = tencentMap?.addPolyline(
            PolylineOptions()
                .level(OverlayLevel.OverlayLevelAboveLabels)
                .zIndex(CASING_Z)
                .addAll(points)
                .width(dpToPx(activeWidthDp + CASING_EXTRA_DP))
                .color(Color.WHITE)
        )
    }

    private fun LegOverlay.displayColor(active: Boolean): Int = when {
        !active -> mapTint(activeColor, 0.40f)
        geometryKind == RouteGeometryKind.STATION_SEQUENCE -> mapTint(activeColor, 0.72f)
        else -> activeColor
    }

    /** 把颜色按比例混到浅色底图上，得到视觉上"半透明"的不透明色 */
    private fun mapTint(color: Int, ratio: Float): Int =
        ColorUtils.blendARGB(MAP_BG, color or 0xFF000000.toInt(), ratio)

    private fun dpToPx(dp: Int): Float = dp * resources.displayMetrics.density

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    // ── 行程信息列表 ──

    private fun bindTripInfo(segments: List<MapSegment>) {
        val container = binding.tripListContainer
        segmentRows.clear()
        rowFades.values.forEach { it.first.cancel() }
        rowFades.clear()
        container.removeAllViews()

        if (segments.isEmpty()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "暂无行程数据"
                    setTextColor(Palette.INK_3)
                    textSize = 12f
                    setPadding(dpToPx(12).toInt(), dpToPx(16).toInt(), dpToPx(12).toInt(), dpToPx(16).toInt())
                }
            )
            return
        }

        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for ((index, seg) in segments.withIndex()) {
            if (index > 0) {
                // 行间分隔线：左右缩进，与设置页卡片内分隔一致
                container.addView(
                    View(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            marginStart = dpToPx(12).toInt(); marginEnd = dpToPx(12).toInt()
                        }
                        setBackgroundColor(Palette.LINE)
                    }
                )
            }

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(4).toInt(); bottomMargin = dpToPx(4).toInt()
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
                // 高亮圆角框与内容之间留间距，避免内容顶着框边缘
                setPadding(dpToPx(12f).toInt(), dpToPx(8f).toInt(), dpToPx(12f).toInt(), dpToPx(8f).toInt())
            }

            val leftCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val routeRow = RouteFlowLayout(requireContext())
            if (seg.hasCurve) {
                // 列表只显示交易记录的最终起终点；换乘过程仅在顶部当前路线栏展示。
                routeRow.addView(stationChip(seg.from.name, seg.from.lineName, seg.from.lineColor))
                routeRow.addView(arrowView())
                routeRow.addView(stationChip(seg.to!!.name, seg.to.lineName, seg.to.lineColor))
            } else {
                routeRow.addView(stationChip(seg.from.name, seg.from.lineName, seg.from.lineColor))
            }

            val timeView = TextView(requireContext()).apply {
                text = if (seg.hasCurve) "${timeFmt.format(Date(seg.startTime))} → ${stopTime(seg.endTime, seg.startTime)}"
                else timeFmt.format(Date(seg.startTime))
                textSize = 11f
                setTextColor(Palette.INK_3)
            }

            leftCol.addView(routeRow)
            leftCol.addView(timeView)
            row.addView(leftCol)

            segmentRows.add(row)
            row.setOnClickListener {
                val i = segmentRows.indexOf(row)
                if (i >= 0) {
                    setCurrentSegment(i)
                    scrollListToCurrent(i)   // 点击的行平滑滚到中间（滑动选择时不滚，跟随手指）
                }
            }
            container.addView(row)
        }
    }

    // ── 生命周期 ──

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { super.onPause(); mapView?.onPause() }
    override fun onStop() { super.onStop(); mapView?.onStop() }

    override fun onDestroyView() {
        haptics?.cancel(); haptics = null
        stopShimmer()
        scrollAnimator?.cancel()
        progressAnimator?.cancel()
        stopRouteClock()
        binding.currentStationPanel.animate().cancel()
        segmentOverlays.forEach { holder -> holder.legs.forEach { it.dispose(removeFromMap = false) } }
        stopPlayback()
        routeGeneration++
        routeLoadJob?.cancel()
        routeLoadJob = null
        mapView?.onDestroy()
        mapView = null
        tencentMap = null
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val GHOST_WIDTH_DP = 1.5f     // 非当前段示意线宽
        const val FLY_SCREENS_PER_SEC = 1.2  // MapLibre flyTo 默认速度（每秒飞过的"屏"数）
        const val FLY_MIN_MS = 900L
        const val FLY_MAX_MS = 3200L
        const val STATION_ZOOM = 15f         // 单站事件的街区级视野
        const val SAME_STATION_METERS = 300.0 // 同名站点记录视为同一车站的距离
        const val FALLBACK_FRAME_ZOOM = 13f  // 视口尚未布局时的整段视野
        const val MIN_FRAME_ZOOM = 9f        // 整段框选的缩放范围
        const val MAX_FRAME_ZOOM = 16f
        const val FRAME_PAD_X_DP = 40f       // 框选留白（逻辑像素）：上方让出提示条
        const val FRAME_PAD_TOP_DP = 64f
        const val FRAME_PAD_BOTTOM_DP = 40f
        const val MAP_BG = 0xFFF4F4F2.toInt()  // 浅色底图的近似底色（用于混出不透明的淡色线）
        const val TRANSITION_MS = 300L      // 当前段切换：线路/列表高亮过渡
        const val SHIMMER_MS = 1800L        // 流光走完一趟的时长（1x）
        const val OVERVIEW_SHIMMER_PASSES = 2 // 播放时整段总览停留 = 流光走完的趟数
        const val PRE_FLY_MS = 1000f        // 播放飞往下一段前的停留（1x）
        const val POST_FLY_MS = 500f        // 播放飞到后切换前的停留（1x）
        const val SHIMMER_HALF = 0.14f      // 流光半宽（占路径比例）
        const val SHIMMER_STRENGTH = 0.55f  // 流光中心向白色混合的强度
        const val SHIMMER_LAYERS = 5        // 流光叠层数（纯色折线，外淡内亮）
        const val SCROLL_MS = 380L          // 列表滚动到当前段
        const val USER_SCROLL_WINDOW_MS = 1500L // 松手后惯性滚动仍算用户滚动的时间窗
        const val CASING_EXTRA_DP = 4f      // 白描边比线路宽出的量（两侧各 2dp）
        // 全部覆盖物同在 AboveLabels 层，按 zIndex 自下而上：
        // 非当前段线路 < 其余站点 < 当前段白描边 < 当前段线路 < 流光各层 < 换乘点 < 当前段两端站点（站名不被遮挡）
        const val LINE_Z = 1
        const val STATION_Z = 2
        const val CASING_Z = 3
        const val ACTIVE_LINE_Z = 4
        const val SHIMMER_Z = 5             // 流光占 SHIMMER_Z ..< SHIMMER_Z + SHIMMER_LAYERS
        const val TRANSFER_Z = SHIMMER_Z + SHIMMER_LAYERS
        const val ACTIVE_STATION_Z = TRANSFER_Z + 1
        const val MARKER_PAD_DP = 3f        // 图标四周留白：给阴影/描边空间，对称以保证居中锚点
        const val LABEL_TEXT_DP = 11f       // 站名标签字号（按 dp 绘制）
        const val LABEL_PAD_X_DP = 7f
        const val LABEL_PAD_Y_DP = 3f
        const val LABEL_TIME_GAP_DP = 5f    // 站名与时间间距
        const val LABEL_GAP_DP = 2f         // 圆点与标签间距
    }
}
