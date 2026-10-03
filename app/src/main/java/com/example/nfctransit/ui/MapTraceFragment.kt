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
    private var shimmerBand: Polyline? = null             // 流光光带（叠在当前段线路之上）
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
    }

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
        if (playing) startPlayback() else { playbackJob?.cancel(); playbackJob = null }
        refreshMarkerIcons()
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        playbackJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                advanceOneStep()
            }
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel(); playbackJob = null
        cameraJob?.cancel(); cameraJob = null
        playing = false
        binding.btnPlay.text = ""
        refreshMarkerIcons()
    }

    private fun pause() {
        playbackJob?.cancel(); playbackJob = null
        cameraJob?.cancel(); cameraJob = null
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

    /** 播放前进一步：飞前停 1s → 镜头飞到下一事件所在整段的总览（await 完成）→ 飞后停 0.5s → 切换。
     *  当前在进站端且后一个事件是它的出站端时，下一步是同一段的出站端（镜头已在总览则不动）。 */
    private suspend fun advanceOneStep() {
        val cur = model.events.getOrNull(currentEventIndex) ?: return
        val seg = activeSegmentAt()
        val journeySegment = seg?.takeIf { it.hasCurve && it.from === cur }
        val next = if (journeySegment != null) {
            val ti = model.events.indexOfFirst { it === journeySegment.to }
            if (ti < 0) return else ti
        } else {
            if (currentEventIndex >= model.events.lastIndex) 0 else currentEventIndex + 1
        }
        // 飞前停 1s
        delay((1000f / speed).toLong())
        val (center, zoom) = cameraFrameFor(model.events[next])
        flyCameraNow(center, zoom)
        // 飞后停 0.5s，然后切换
        delay((500f / speed).toLong())
        currentEventIndex = next
        updateHighlight()
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
        val previous = lastActiveSegment
        lastActiveSegment = active
        for (holder in segmentOverlays) {
            val isActive = active != null && holder.segment === active
            val changed = holder.segment === active && active !== previous ||
                holder.segment === previous && previous !== active
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
        if (active !== previous || shimmerAnimator == null) {
            stopShimmer()
            segmentOverlays.firstOrNull { it.segment === active }?.let(::startShimmer)
        }
        renderTransferMarkers(active)

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
            if (bg.alpha == target) continue
            // 只有当前段真正切换时才渐变；列表重建（路线加载后）直接定状态，避免闪烁
            if (activeIdx != highlightedRow && (i == activeIdx || i == highlightedRow)) {
                ObjectAnimator.ofInt(bg, "alpha", bg.alpha, target).setDuration(TRANSITION_MS).start()
            } else {
                bg.alpha = target
            }
        }
        highlightedRow = activeIdx
        if (scrollList) scrollListToCurrent(activeIdx)

        // 文案：站名 + 药丸线路
        renderCurrentStation(active, ev)

        val frac = if (model.events.size <= 1) 1f else currentEventIndex.toFloat() / (model.events.size - 1)
        updateProgressBar(frac)
    }

    private fun updateProgressBar(frac: Float) {
        val parent = binding.progressPlayback.parent as? View
        val totalW = parent?.width ?: 0
        binding.progressPlayback.layoutParams = binding.progressPlayback.layoutParams.apply {
            width = (totalW * frac.coerceIn(0f, 1f)).toInt()
        }
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

    /** 当前站点行：站名（药丸线路）-> 站名（药丸线路），下面小字显示时间 */
    private fun renderCurrentStation(active: MapSegment?, ev: MapEvent) {
        val row = binding.currentStationRow
        row.removeAllViews()
        val plan = active?.let(routePlans::get)
        if (
            active != null &&
            plan != null &&
            shouldShowFullCurrentTripRoute(currentTripRouteDisplayMode, plan.transitLegs.size)
        ) {
            addRouteChain(row, active, plan)
        } else if (active != null && active.hasCurve && active.to != null) {
            row.addView(stationChip(active.from.name, active.from.lineName, active.from.lineColor))
            row.addView(arrowView())
            row.addView(stationChip(active.to.name, active.to.lineName, active.to.lineColor))
        } else {
            row.addView(stationChip(ev.name, ev.lineName, ev.lineColor))
        }
        binding.tvCurrentTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ev.timeMillis))
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

    /** 原版 MaptileGL/MapLibre flyTo：Van Wijk「smooth and efficient zooming and panning」最优路径。
     *  镜头按最优曲线先拉远（zoom 下降）再拉近（zoom 回升），路径与缩放幅度随距离自适应。
     *  suspend：播放流程里 await 完成（飞后停 2s 再切换）。 */
    private suspend fun flyToNow(map: TencentMap, target: LatLng, targetZoom: Float, speedFactor: Float = 1f) {
        val cp = map.cameraPosition ?: return
        val start = cp.target
        val startZoom = cp.zoom

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
        fun cosh(n: Double) = (Math.exp(n) + Math.exp(-n)) / 2.0
        fun sinh(n: Double) = (Math.exp(n) - Math.exp(-n)) / 2.0
        fun tanh(n: Double) = sinh(n) / cosh(n)

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
        // 按显示帧推进；进度用"加速-匀速-减速"曲线：只在首尾 25% 缓入缓出，中段匀速（峰速仅为均速 1.33 倍）
        val startNanos = awaitFrame()
        while (currentCoroutineContext().isActive) {
            val frameNanos = awaitFrame()
            val t = ((frameNanos - startNanos) / 1_000_000.0 / durationMs).coerceAtMost(1.0)
            if (t >= 1.0) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(target, targetZoom))
                break
            }
            val k = cruiseEasing(t)
            val s = k * S
            // 与 MapLibre 原版一致：不加任何防御 clamp
            val w = if (pureZoom) Math.exp(k * rho * S) else cosh(r0) / cosh(r0 + rho * s)
            val centerFactor = if (pureZoom) 0.0
            else w0 * (cosh(r0) * tanh(r0 + rho * s) - sinh(r0)) / rho2 / u1
            val zoom = (startZoom + scaleZoom(1.0 / w)).toFloat().coerceIn(3f, 21f)
            val (lng, lat) = worldToLatLng(from.first + dx * centerFactor, from.second + dy * centerFactor, startWorldSize)
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
        val currentEvent = model.events.getOrNull(currentEventIndex)
        val active = activeSegmentAt()
        // 高亮站点 → 本条记录里的线路色（换乘站被多条线共用，默认圆点色只取了首次出现的线路）
        val recordColors = mutableMapOf<Long, Int?>()
        fun key(stationId: Long) = stationMarkerKey[stationId] ?: stationId
        currentEvent?.let { recordColors[key(it.stationId)] = parseColor(it.lineColor) }
        if (active != null && active.hasCurve) {
            recordColors[key(active.from.stationId)] = parseColor(active.from.lineColor)
            active.to?.let { recordColors[key(it.stationId)] = parseColor(it.lineColor) }
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
            val iconKey = "$labeled|${color and 0xFFFFFF}|$highlighted|$dotRadiusDp"
            if (stationIconKeys[sid] == iconKey) continue
            stationIconKeys[sid] = iconKey
            val icon = if (labeled) {
                labelCache.getOrPut("$name|${color and 0xFFFFFF}|$highlighted") { labeledDot(color, name, highlighted) }
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
    private fun labeledDot(color: Int, name: String, highlighted: Boolean): BitmapDescriptor {
        val d = resources.displayMetrics.density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = LABEL_TEXT_DP * d
            this.color = if (highlighted) Palette.INK else Palette.INK_2
            typeface = if (highlighted) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        val textW = textPaint.measureText(name)
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
     * 不改线路本身的颜色（逐点 setColors 在真实路线点串上会让地图引擎原生崩溃），而是另画一条短折线
     * 叠在当前段线路之上：每帧按固定 [SHIMMER_POINTS] 个等距点重采样路径上的一小段，逐点颜色从线路色
     * 渐亮到中心再渐回线路色——两端与下面的线路同色、看不出边界，效果与原先整线逐点流光一致。
     * 点数固定不变，setPoints / setColors 的长度始终一致。多条腿按距离视为一条路径。
     */
    private fun startShimmer(holder: SegmentOverlay) {
        val path = ShimmerPath.of(holder.legs) ?: return
        shimmerAnimator = ValueAnimator.ofFloat(-SHIMMER_HALF, 1f + SHIMMER_HALF).apply {
            duration = SHIMMER_MS
            startDelay = TRANSITION_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { va ->
                if (holder.legs.any { it.removed }) return@addUpdateListener
                val center = va.animatedValue as Float
                shimmerBand = drawShimmerBand(shimmerBand, path, center)
            }
            start()
        }
    }

    /** 在 [center ± SHIMMER_HALF]（占全长比例，夹在路径内）重采样出光带；区间退化时移除（腾讯折线不认 visible） */
    private fun drawShimmerBand(band: Polyline?, path: ShimmerPath, center: Float): Polyline? {
        val from = (center - SHIMMER_HALF).coerceIn(0f, 1f)
        val to = (center + SHIMMER_HALF).coerceIn(0f, 1f)
        if (to - from < 0.01f) {
            band?.remove()
            return null
        }
        val n = SHIMMER_POINTS
        val xs = FloatArray(n) { i -> from + (to - from) * i / (n - 1) }
        val pts = xs.map { path.pointAt(it.toDouble()) }
        val colors = IntArray(n) { i ->
            val d = kotlin.math.abs(xs[i] - center) / SHIMMER_HALF
            val k = if (d >= 1f) 0f else (1f - d) * (1f - d)   // 中心最亮、两侧柔和衰减到线路本色
            ColorUtils.blendARGB(path.colorAt(xs[i].toDouble()), Color.WHITE, SHIMMER_STRENGTH * k)
        }
        val indexes = IntArray(n) { it }
        val widthPx = dpToPx(path.widthAt(center.toDouble()))
        if (band == null) {
            return tencentMap?.addPolyline(
                PolylineOptions()
                    .level(OverlayLevel.OverlayLevelAboveLabels)
                    .zIndex(SHIMMER_Z)
                    .addAll(pts)
                    .width(widthPx)
                    .gradient(true)
                    .colors(colors, indexes)
            )
        }
        band.setPoints(pts)
        band.setColors(colors, indexes)
        band.setWidth(widthPx)
        return band
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        shimmerBand?.remove(); shimmerBand = null
    }

    /** 当前段整条路径（各腿首尾相接）：按累计距离截取子路径、查询某处的线路色与线宽 */
    private class ShimmerPath(
        private val points: List<LatLng>,
        private val dist: DoubleArray,
        private val colors: IntArray,
        private val widths: FloatArray
    ) {
        private val total = dist.last()

        private fun indexAt(d: Double): Int {
            var lo = 0
            var hi = dist.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (dist[mid] <= d) lo = mid else hi = mid
            }
            return lo
        }

        private fun indexAtFraction(fraction: Double) = indexAt(fraction.coerceIn(0.0, 1.0) * total)

        fun pointAt(fraction: Double): LatLng = pointAtDistance(fraction.coerceIn(0.0, 1.0) * total)

        private fun pointAtDistance(d: Double): LatLng {
            val i = indexAt(d)
            val j = (i + 1).coerceAtMost(points.size - 1)
            val span = dist[j] - dist[i]
            val t = if (span <= 0.0) 0.0 else ((d - dist[i]) / span).coerceIn(0.0, 1.0)
            val a = points[i]; val b = points[j]
            return LatLng(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
        }

        fun colorAt(fraction: Double): Int = colors[indexAtFraction(fraction)]

        fun widthAt(fraction: Double): Float = widths[indexAtFraction(fraction)]

        companion object {
            /** 用当前段各腿的点与基础颜色建路径；相邻重复点（腿首尾相接处）去掉 */
            fun of(legs: List<LegOverlay>): ShimmerPath? {
                val pts = ArrayList<LatLng>()
                val cols = ArrayList<Int>()
                val ws = ArrayList<Float>()
                for (leg in legs) {
                    val base = leg.activeColors ?: continue
                    for ((i, p) in leg.points.withIndex()) {
                        val last = pts.lastOrNull()
                        if (last != null && last.latitude == p.latitude && last.longitude == p.longitude) continue
                        pts.add(p)
                        cols.add(if (base.size == 1) base[0] else base[i.coerceAtMost(base.size - 1)])
                        ws.add(leg.activeWidthDp)
                    }
                }
                if (pts.size < 2) return null
                val dist = DoubleArray(pts.size)
                for (k in 1 until pts.size) dist[k] = dist[k - 1] + approxMeters(pts[k - 1], pts[k])
                if (dist.last() <= 0.0) return null
                return ShimmerPath(pts, dist, cols.toIntArray(), ws.toFloatArray())
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
                text = timeFmt.format(Date(seg.startTime))
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
        stopShimmer()
        scrollAnimator?.cancel()
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
        const val SHIMMER_MS = 1800L        // 流光走完一趟的时长
        const val SHIMMER_HALF = 0.14f      // 流光半宽（占路径比例）
        const val SHIMMER_STRENGTH = 0.55f  // 流光中心向白色混合的强度
        const val SHIMMER_POINTS = 32       // 光带重采样点数（固定，保证点/色数组等长）
        const val SCROLL_MS = 380L          // 列表滚动到当前段
        const val USER_SCROLL_WINDOW_MS = 1500L // 松手后惯性滚动仍算用户滚动的时间窗
        const val CASING_EXTRA_DP = 4f      // 白描边比线路宽出的量（两侧各 2dp）
        // 全部覆盖物同在 AboveLabels 层，按 zIndex 自下而上：
        // 非当前段线路 < 其余站点 < 当前段白描边 < 当前段线路 < 流光 < 换乘点 < 当前段两端站点（站名不被遮挡）
        const val LINE_Z = 1
        const val STATION_Z = 2
        const val CASING_Z = 3
        const val ACTIVE_LINE_Z = 4
        const val SHIMMER_Z = 5
        const val TRANSFER_Z = 6
        const val ACTIVE_STATION_Z = 7
        const val MARKER_PAD_DP = 3f        // 图标四周留白：给阴影/描边空间，对称以保证居中锚点
        const val LABEL_TEXT_DP = 11f       // 站名标签字号（按 dp 绘制）
        const val LABEL_PAD_X_DP = 7f
        const val LABEL_PAD_Y_DP = 3f
        const val LABEL_GAP_DP = 2f         // 圆点与标签间距
    }
}
