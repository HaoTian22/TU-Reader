package com.example.nfctransit.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.nfctransit.R
import com.example.nfctransit.databinding.FragmentMapTraceBinding
import androidx.navigation.fragment.findNavController
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
import com.tencent.tencentmap.mapsdk.maps.model.Polyline
import com.tencent.tencentmap.mapsdk.maps.model.PolylineOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 地图轨迹：腾讯地图展示行程。从数据库交易构建时间线（MapJourney），
 * 站点画圆点、站间画贝塞尔曲线（进站→出站），播放时高亮当前站并让相机沿曲线缓慢移动。
 */
class MapTraceFragment : Fragment(R.layout.fragment_map_trace) {

    /** 一条贝塞尔曲线：白色描边底 + 彩色主线（描边仅当前段显示） */
    private class CurveOverlay(val white: Polyline, val color: Polyline)

    private var _binding: FragmentMapTraceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels({ requireActivity() })

    private var mapView: MapView? = null
    private var tencentMap: TencentMap? = null

    private var model: JourneyModel = JourneyModel.build(emptyList())
    private var currentEventIndex = 0
    private var playing = false
    private var speed = 1f
    private var mainAccent = 0xFF0066FF.toInt()

    private val stationMarkers = mutableMapOf<Long, Marker>()
    private val stationMeta = mutableMapOf<Long, Pair<String, Int>>()   // stationId -> (站名, 圆点色)
    private val segmentPolylines = mutableListOf<Pair<MapSegment, CurveOverlay>>()
    private val segmentRows = mutableListOf<View>()          // 行程列表行（与 model.segments 对齐）
    private val plainDotCache = mutableMapOf<Int, BitmapDescriptor>()
    private val highlightDotCache = mutableMapOf<Int, BitmapDescriptor>()
    private val labelCache = mutableMapOf<String, BitmapDescriptor>()  // "站名|颜色|高亮" -> 带站名标签的圆点
    private var namesShown = false     // 当前 zoom≥13 是否显示站名标签
    private var dotRadiusDp = 4.5f     // 当前普通圆点半径（随缩放变化）
    private var needIconRefresh = false   // 相机移动中标记，移动结束后一次性重建图标

    private val ghostCurveColor = 0x305977B2.toInt()   // 非当前段曲线：暗蓝灰半透明

    private var programmaticScroll = false   // 播放自动滚动列表时不当作"用户滑动"
    private var playbackJob: Job? = null
    private var cameraJob: Job? = null

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

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        // 主题色跟随卡片；进度条填充改圆角
        viewModel.mainAccent.observe(viewLifecycleOwner) { accent ->
            mainAccent = accent.toInt()
            binding.progressPlayback.background = GradientDrawable().apply {
                cornerRadius = 2f * resources.displayMetrics.density
                setColor(mainAccent)
            }
            binding.tvCardBadge.setTextColor(mainAccent)
        }

        viewModel.selectedCard.observe(viewLifecycleOwner) { card ->
            if (card != null) {
                binding.tvCardBadge.text = "${card.cardType} · ${card.lastFour}"
                binding.tvCardBadge.setTextColor(card.gradientStartColor.toInt())
            }
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
        val fa = Typeface.createFromAsset(requireContext().assets, "fonts/fa-solid-900.ttf")
        binding.btnPrev.typeface = fa
        binding.btnPlay.typeface = fa
        binding.btnNext.typeface = fa
        binding.btnPrev.text = ""   // fa-backward-fast（⏮ 上一行程）
        binding.btnPlay.text = if (playing) "" else ""   // fa-pause / fa-play
        binding.btnNext.text = ""   // fa-forward-fast（⏭ 下一行程）

        binding.btnPlay.setOnClickListener { togglePlay() }
        binding.btnPrev.setOnClickListener { if (model.events.isNotEmpty()) { pause(); jumpTo(currentEventIndex - 1) } }
        binding.btnNext.setOnClickListener { if (model.events.isNotEmpty()) { pause(); jumpTo(currentEventIndex + 1) } }
        binding.chip05x.setOnClickListener { setSpeed(0.5f) }
        binding.chip1x.setOnClickListener { setSpeed(1f) }
        binding.chip2x.setOnClickListener { setSpeed(2f) }

        // 列表：触摸即暂停，滑动调整当前时间（像歌词那样跟随视口中线）
        binding.tripScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pause()
            false
        }
        binding.tripScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!programmaticScroll) handleUserScroll(scrollY)
        }
    }

    private fun setSpeed(v: Float) {
        speed = v
        val selectedBg = R.drawable.bg_speed_chip_selected
        val normalBg = R.drawable.bg_speed_chip
        fun style(chip: TextView, isSel: Boolean) {
            chip.setBackgroundResource(if (isSel) selectedBg else normalBg)
            chip.setTextColor(if (isSel) Color.BLACK else 0xFF8899AA.toInt())
        }
        style(binding.chip05x, v == 0.5f)
        style(binding.chip1x, v == 1f)
        style(binding.chip2x, v == 2f)
    }

    // ── 渲染 ──

    private fun renderJourney() {
        stopPlayback()
        stationMarkers.clear()
        stationMeta.clear()
        segmentPolylines.clear()
        segmentRows.clear()
        plainDotCache.clear()
        highlightDotCache.clear()
        labelCache.clear()
        tencentMap?.clear()

        if (model.isEmpty) {
            binding.currentStationRow.removeAllViews()
            binding.currentStationRow.addView(
                TextView(requireContext()).apply {
                    text = "暂无行程数据"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
            )
            binding.tvCurrentTime.text = ""
            bindTripInfo(emptyList())
            return
        }

        // 站点圆点（按站去重）
        for (ev in model.events) {
            if (ev.stationId in stationMarkers) continue
            val color = parseColor(ev.lineColor) ?: 0xFF8899AA.toInt()
            stationMeta[ev.stationId] = ev.name to color
            val m = tencentMap?.addMarker(
                MarkerOptions()
                    .position(LatLng(ev.lat, ev.lng))
                    .anchor(0.5f, 0.5f)
                    .icon(markerDot(color, highlighted = false))
            )
            if (m != null) stationMarkers[ev.stationId] = m
        }

        // 站间贝塞尔曲线：白色描边底 + 彩色主线（描边仅当前段显示）
        for (seg in model.segments) {
            if (!seg.hasCurve) continue
            val pts = bezierPoints(seg.from, seg.to!!)
            val color = parseColor(seg.lineColor) ?: 0xFF4A90D9.toInt()
            val white = tencentMap?.addPolyline(
                PolylineOptions().addAll(pts).width(dpToPx(6f)).color(0xFFFFFFFF.toInt()).visible(false)
            )
            val colored = tencentMap?.addPolyline(
                PolylineOptions().addAll(pts).width(dpToPx(0.5f)).color(ghostCurveColor)
            )
            if (white != null && colored != null) segmentPolylines.add(seg to CurveOverlay(white, colored))
        }

        currentEventIndex = 0
        updateHighlight()
        // 起始直接定位到第一个站点 zoom18（不先全景缩到全省，避免几十 km 比例尺）
        val first = model.events[0]
        tencentMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(first.lat, first.lng), 18f))
        bindTripInfo(model.segments)

        // 等布局完成后再刷一次进度条宽度（首帧父容器宽度尚未算出）
        view?.post { updateHighlight() }
    }

    // ── 播放 ──

    private fun togglePlay() {
        if (model.events.isEmpty()) return
        playing = !playing
        binding.btnPlay.text = if (playing) "" else ""
        if (playing) startPlayback() else { playbackJob?.cancel(); playbackJob = null }
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
    }

    private fun pause() {
        playbackJob?.cancel(); playbackJob = null
        cameraJob?.cancel(); cameraJob = null
        playing = false
        binding.btnPlay.text = ""
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

    /** 播放前进一步：飞前停 1s → flyTo（await 完成）→ 飞后停 2s → 切换。
     *  当前在进站端且后一个事件是它的出站端 → 飞到出站端；否则直移下个事件。
     *  时间线起点/终点时镜头 zoom 到 18（站点特写）。 */
    private suspend fun advanceOneStep() {
        val cur = model.events.getOrNull(currentEventIndex) ?: return
        val seg = activeSegmentAt()
        val isJourney = seg != null && seg.hasCurve && seg.from === cur
        val next = if (isJourney) {
            val ti = model.events.indexOfFirst { it === seg!!.to }
            if (ti < 0) return else ti
        } else {
            if (currentEventIndex >= model.events.lastIndex) 0 else currentEventIndex + 1
        }
        // 飞前停 1s
        delay((1000f / speed).toLong())
        // flyTo（等待完成）
        val zoom = if (next == 0 || next == model.events.lastIndex) 18f else null
        animateCameraToNow(model.events[next], zoom = zoom)
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

        // 贝塞尔：当前行程段高亮加粗（白描边），其余统一变细变暗
        for ((seg, ov) in segmentPolylines) {
            val isActive = active != null && seg === active
            val color = parseColor(seg.lineColor) ?: 0xFF4A90D9.toInt()
            if (isActive) {
                ov.white.setVisible(true)
                ov.color.setColor(color)
                ov.color.setWidth(dpToPx(5f))
            } else {
                ov.white.setVisible(false)
                ov.color.setColor(ghostCurveColor)
                ov.color.setWidth(dpToPx(0.5f))
            }
        }

        // 列表：当前行程行高亮（圆角）；播放/跳转时把它滚到视口中间
        val activeIdx = active?.let { model.segments.indexOf(it) } ?: -1
        for ((i, row) in segmentRows.withIndex()) {
            if (i == activeIdx) {
                row.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(8f)
                    setColor(0x224488CC)
                }
            } else {
                row.background = null
            }
        }
        if (scrollList) scrollListToCurrent(activeIdx)

        // 文案：站名 + 药丸线路
        renderCurrentStation(active, ev)

        // 进度条
        val parent = binding.progressPlayback.parent as? View
        val totalW = parent?.width ?: 0
        val frac = if (model.events.size <= 1) 1f else currentEventIndex.toFloat() / (model.events.size - 1)
        binding.progressPlayback.layoutParams = binding.progressPlayback.layoutParams.apply {
            width = (totalW * frac).toInt()
        }
    }

    /** 把当前行程行滚动到列表视口中间（播放跟随） */
    private fun scrollListToCurrent(segIndex: Int) {
        if (segIndex !in segmentRows.indices) return
        val row = segmentRows[segIndex]
        val sv = binding.tripScroll
        val target = (row.top - (sv.height - row.height) / 2).coerceAtLeast(0)
        if (sv.scrollY != target) {
            programmaticScroll = true
            sv.scrollTo(0, target)
            programmaticScroll = false
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
            animateCameraTo(model.events[currentEventIndex], durationMs = 650L)
        }
    }

    /** 当前站点行：站名（药丸线路）-> 站名（药丸线路），下面小字显示时间 */
    private fun renderCurrentStation(active: MapSegment?, ev: MapEvent) {
        val row = binding.currentStationRow
        row.removeAllViews()
        if (active != null && active.hasCurve && active.to != null) {
            row.addView(stationChip(active.from.name, active.from.lineName, active.from.lineColor))
            row.addView(arrowView())
            row.addView(stationChip(active.to.name, active.to.lineName, active.to.lineColor))
        } else {
            row.addView(stationChip(ev.name, ev.lineName, ev.lineColor))
        }
        binding.tvCurrentTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ev.timeMillis))
    }

    /** 站名 + 线路药丸 的组合视图 */
    private fun stationChip(name: String, lineName: String, lineColor: String?): LinearLayout {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val nameTv = TextView(requireContext()).apply {
            text = name
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
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
        setTextColor(0xFF8899AA.toInt())
        textSize = 14f
        setPadding(dpToPx(4f).toInt(), 0, dpToPx(4f).toInt(), 0)
    }

    /** 镜头到目标站点：maptileGL flyTo 式——先拉升视野（zoom 下降）再俯冲收拢（zoom 回升），平滑缓动。
     *  @param zoom 指定目标缩放（开始/结束 zoom=18）；null 保持当前视野 */
    /** 手动/滑动调用：异步 flyTo（launch cameraJob） */
    private fun animateCameraTo(ev: MapEvent, durationMs: Long = 1100L, zoom: Float? = null) {
        val map = tencentMap ?: return
        val targetZoom = zoom ?: (map.cameraPosition?.zoom?.let { maxOf(it, 14.5f) } ?: 14.5f)
        val cp = map.cameraPosition
        if (cp == null) {
            // 相机尚未就绪：直接定位目标，保证起始/终点能落到指定 zoom（如 18）
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(ev.lat, ev.lng), targetZoom))
            return
        }
        cameraJob?.cancel()
        cameraJob = viewLifecycleOwner.lifecycleScope.launch {
            flyToNow(map, LatLng(ev.lat, ev.lng), targetZoom, durationMs)
        }
    }

    /** 播放调用：同步 flyTo，await 完成（用于飞前/飞后停时的节奏控制） */
    private suspend fun animateCameraToNow(ev: MapEvent, durationMs: Long = 1100L, zoom: Float? = null) {
        val map = tencentMap ?: return
        val targetZoom = zoom ?: (map.cameraPosition?.zoom?.let { maxOf(it, 14.5f) } ?: 14.5f)
        val cp = map.cameraPosition
        if (cp == null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(ev.lat, ev.lng), targetZoom))
            return
        }
        cameraJob?.cancel()
        flyToNow(map, LatLng(ev.lat, ev.lng), targetZoom, durationMs)
    }

    /** 原版 MaptileGL/MapLibre flyTo：Van Wijk「smooth and efficient zooming and panning」最优路径。
     *  镜头按最优曲线先拉远（zoom 下降）再拉近（zoom 回升），路径与缩放幅度随距离自适应。
     *  suspend：播放流程里 await 完成（飞后停 2s 再切换）。 */
    private suspend fun flyToNow(map: TencentMap, target: LatLng, targetZoom: Float, durationMs: Long) {
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
        Log.d("FlyTo", "start z=$startZoom -> t=$targetZoom " +
            "dist=${"%.0f".format(distanceMeters(start, target))}m u1=${"%.0f".format(u1)}px " +
            "w0=${"%.0f".format(w0)} rho=${"%.2f".format(rho)} S=${"%.2f".format(S)} r0=${"%.2f".format(r0)} pureZoom=$pureZoom")

        val samples = 44
        val stepMs = (durationMs / samples).coerceAtLeast(16L)
        for (i in 0..samples) {
                if (!currentCoroutineContext().isActive) break
                val k = i.toDouble() / samples
                val s = k * S
                // 与 MapLibre 原版一致：不加任何防御 clamp
                val w = if (pureZoom) Math.exp(k * rho * S) else cosh(r0) / cosh(r0 + rho * s)
                val centerFactor = if (pureZoom) 0.0
                else w0 * (cosh(r0) * tanh(r0 + rho * s) - sinh(r0)) / rho2 / u1
                val zoom = if (i == samples) targetZoom
                else (startZoom + scaleZoom(1.0 / w)).toFloat().coerceIn(3f, 21f)
                val px = from.first + dx * centerFactor
                val py = from.second + dy * centerFactor
                val (lng, lat) = if (i == samples) target.longitude to target.latitude
                else worldToLatLng(px, py, startWorldSize)
                if (i % 6 == 0 || i == samples) {
                    Log.d("FlyTo", "k=${"%.2f".format(k)} zoom=${"%.1f".format(zoom)} " +
                        "cf=${"%.2f".format(centerFactor)} lat=${"%.5f".format(lat)} lng=${"%.5f".format(lng)}")
                }
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom))
                delay(stepMs)
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

    /** 按当前 zoom 与选中态刷新全部站点标记：zoom≥13 显示站名标签，否则纯圆点。
     *  白描边集合 = 当前站 + 当前行程段的两端（选中段时两边都高亮描边）。 */
    private fun refreshMarkerIcons() {
        val currentStationId = model.events.getOrNull(currentEventIndex)?.stationId
        val active = activeSegmentAt()
        val outlined = mutableSetOf<Long>()
        currentStationId?.let { outlined.add(it) }
        if (active != null && active.hasCurve) {
            outlined.add(active.from.stationId)
            active.to?.let { outlined.add(it.stationId) }
        }
        for ((sid, marker) in stationMarkers) {
            val meta = stationMeta[sid] ?: continue
            val (name, color) = meta
            val highlighted = sid in outlined
            if (namesShown) {
                val key = "$name|${color and 0xFFFFFF}|$highlighted"
                marker.setIcon(labelCache.getOrPut(key) { labeledDot(color, name, highlighted) })
                marker.setAnchor(0.5f, labelAnchorV(highlighted))
            } else {
                marker.setIcon(markerDot(color, highlighted))
                marker.setAnchor(0.5f, 0.5f)
            }
        }
    }

    /** 圆点在上、站名标签在下的图标；anchor 垂直比例固定（高度与站名长度无关） */
    private fun labeledDot(color: Int, name: String, highlighted: Boolean): BitmapDescriptor {
        val density = resources.displayMetrics.density
        val textSize = 11f * density
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.textSize = textSize
            this.color = 0xFFEEEEEE.toInt()
        }
        val textW = textPaint.measureText(name)
        val dotR = (if (highlighted) dotRadiusDp * 1.6f else dotRadiusDp) * density
        val padX = 5f * density
        val labelH = textSize + 4f * density
        val topPad = 2f * density        // 顶部留白：白描边不被裁掉
        val dotDia = dotR * 2f
        val width = maxOf(dotDia + 2f * density, textW + padX * 2f)
        val height = dotDia + labelH + topPad
        val bmp = Bitmap.createBitmap(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 站名深色圆角底条
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x99000000.toInt() }
        val labelLeft = (width - textW - padX * 2f) / 2f
        val labelTop = dotDia + topPad
        canvas.drawRoundRect(labelLeft, labelTop, labelLeft + textW + padX * 2f, labelTop + labelH, 4f * density, 4f * density, bgPaint)
        canvas.drawText(name, labelLeft + padX, labelTop + 2f * density + textSize, textPaint)

        // 圆点（水平居中，顶部留白内）
        val cx = width / 2f
        val cy = dotR + topPad
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, dotR, dotPaint)
        if (highlighted) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                this.style = Paint.Style.STROKE
                this.strokeWidth = 2f * density
            }
            canvas.drawCircle(cx, cy, dotR, ringPaint)
        }
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    /** 带标签图标的锚点：让圆点中心对准坐标（标签在圆点下方，顶部留白内） */
    private fun labelAnchorV(highlighted: Boolean): Float {
        val dotR = if (highlighted) dotRadiusDp * 1.6f else dotRadiusDp
        val topPad = 2f   // 与 labeledDot 顶部留白一致（dp）
        val labelH = 11f + 4f   // 与 labeledDot 中 textSize(11sp) + 4dp 一致
        return (dotR + topPad) / (dotR * 2f + labelH + topPad)
    }

    private fun stationDot(fill: Int, radiusDp: Float, ring: Boolean): BitmapDescriptor {
        val density = resources.displayMetrics.density
        val r = radiusDp * density
        val stroke = if (ring) 2f * density else 0f
        val size = ((r + stroke) * 2 + 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawCircle(size / 2f, size / 2f, r, paint)
        if (ring) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * density
            paint.color = Color.WHITE
            canvas.drawCircle(size / 2f, size / 2f, r, paint)
        }
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun parseColor(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try { Color.parseColor(hex) } catch (e: Exception) { null }
    }

    private fun dpToPx(dp: Int): Float = dp * resources.displayMetrics.density

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    // ── 行程信息列表 ──

    private fun bindTripInfo(segments: List<MapSegment>) {
        val container = binding.tripListContainer
        segmentRows.clear()
        if (container.childCount > 1) container.removeViews(1, container.childCount - 1)

        if (segments.isEmpty()) {
            container.addView(
                TextView(requireContext()).apply {
                    text = "暂无行程数据"
                    setTextColor(0xFF8899AA.toInt())
                    textSize = 12f
                    setPadding(0, dpToPx(16).toInt(), 0, dpToPx(16).toInt())
                }
            )
            return
        }

        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (seg in segments) {
            container.addView(
                View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(0x331B3A5C.toInt())
                }
            )

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(10).toInt(); bottomMargin = dpToPx(10).toInt()
                }
                gravity = android.view.Gravity.CENTER_VERTICAL
                // 高亮圆角框与内容之间留间距，避免内容顶着框边缘
                setPadding(dpToPx(12f).toInt(), dpToPx(6f).toInt(), dpToPx(12f).toInt(), dpToPx(6f).toInt())
            }

            val leftCol = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val routeRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            if (seg.hasCurve) {
                routeRow.addView(stationChip(seg.from.name, seg.from.lineName, seg.from.lineColor))
                routeRow.addView(arrowView())
                routeRow.addView(stationChip(seg.to!!.name, seg.to.lineName, seg.to.lineColor))
            } else {
                routeRow.addView(stationChip(seg.from.name, seg.from.lineName, seg.from.lineColor))
            }

            val timeView = TextView(requireContext()).apply {
                text = timeFmt.format(Date(seg.startTime))
                textSize = 11f
                setTextColor(0xFF668899.toInt())
            }

            leftCol.addView(routeRow)
            leftCol.addView(timeView)
            row.addView(leftCol)

            segmentRows.add(row)
            row.setOnClickListener {
                val i = segmentRows.indexOf(row)
                if (i >= 0) setCurrentSegment(i)
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
        stopPlayback()
        mapView?.onDestroy()
        mapView = null
        tencentMap = null
        _binding = null
        super.onDestroyView()
    }
}
