import SwiftUI

/// Freehand signature pad (`<native:signature-pad>`).
///
/// Ink is exchanged with PHP as an SVG path `d` string in a fixed 360x120
/// coordinate space: absolute `M x y` per stroke followed by `L x y` points,
/// one decimal place, space separated. Touch points are mapped from the view's
/// real size into that space with uniform scale + centered letterboxing, so the
/// saved path is correct regardless of the view's aspect ratio.
///
/// Props (see PTeal79\SignaturePad\Elements\SignaturePad):
///   value          string  existing ink, same path format
///   on_change      int     callback id; fires on stroke end with the FULL path
///                          ("" = cleared). Rides the TEXT_CHANGE event.
///   clear_token    string  changes => wipe pad + fire on_change("")
///   read_only      bool    show ink, ignore touches
///   stroke_width   float   points
///   pen_color      string  default black
///   dark_pen_color string  default: pen_color if set, else white
struct PTealSignaturePadRenderer: View {
    let node: NativeUINode

    @Environment(\.colorScheme) private var colorScheme

    /// Committed strokes.
    @State private var strokes: [[PTealSigPoint]]
    /// Stroke currently being drawn.
    @State private var live: [PTealSigPoint] = []
    /// Last path string this pad produced or loaded. A `value` prop equal to it
    /// is an echo of our own output and must not reload the ink.
    @State private var lastSent: String
    @State private var lastToken: String
    @State private var inGesture: Bool = false
    @State private var ignoreUntilEnd: Bool = false

    /// Squared minimum spacing (in 360x120 units) between kept points.
    private static let minPointDistanceSq: CGFloat = 0.25

    init(node: NativeUINode) {
        self.node = node
        let value = node.props.getString("value")
        _strokes = State(initialValue: PTealSigPath.parse(value))
        _lastSent = State(initialValue: value)
        _lastToken = State(initialValue: node.props.getString("clear_token"))
    }

    var body: some View {
        let p = node.props
        let value = p.getString("value")
        let clearToken = p.getString("clear_token")
        let readOnly = p.getBool("read_only")
        let rawWidth = p.getFloat("stroke_width", default: 2.5)
        let strokeWidth = CGFloat(rawWidth > 0 ? rawWidth : 2.5)
        let onChangeCb = p.getCallbackId("on_change")
        let pen = penColor(p)

        GeometryReader { geo in
            let frame = PTealSigFrame(width: geo.size.width, height: geo.size.height)

            Canvas { ctx, _ in
                guard frame.valid else { return }
                let style = StrokeStyle(lineWidth: strokeWidth, lineCap: .round, lineJoin: .round)
                for s in strokes {
                    drawStroke(ctx, s, frame, pen, style)
                }
                if !live.isEmpty {
                    drawStroke(ctx, live, frame, pen, style)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(Rectangle())
            .highPriorityGesture(
                DragGesture(minimumDistance: 0, coordinateSpace: .local)
                    .onChanged { drag in
                        handleChanged(drag.location, frame)
                    }
                    .onEnded { drag in
                        handleEnded(drag.location, frame, onChangeCb)
                    },
                including: readOnly ? .none : .all
            )
        }
        .onChange(of: value) { _, newValue in
            // Programmatic value update (anything that is not an echo of our output).
            if newValue != lastSent {
                strokes = PTealSigPath.parse(newValue)
                live = []
                lastSent = newValue
                if inGesture { ignoreUntilEnd = true }
            }
        }
        .onChange(of: clearToken) { _, newToken in
            // Never fires for the initial render: lastToken is seeded in init.
            if newToken != lastToken {
                lastToken = newToken
                strokes = []
                live = []
                if inGesture { ignoreUntilEnd = true }
                emit("", onChangeCb)
            }
        }
    }

    // MARK: - Gesture handling

    private func handleChanged(_ location: CGPoint, _ frame: PTealSigFrame) {
        guard frame.valid, !ignoreUntilEnd else { return }
        inGesture = true
        let pt = frame.toLogical(location)
        guard let last = live.last else {
            live = [pt]
            return
        }
        let dx = pt.x - last.x
        let dy = pt.y - last.y
        if dx * dx + dy * dy >= Self.minPointDistanceSq {
            live.append(pt)
        }
    }

    private func handleEnded(_ location: CGPoint, _ frame: PTealSigFrame, _ cb: Int) {
        defer {
            inGesture = false
            ignoreUntilEnd = false
        }
        guard frame.valid, !ignoreUntilEnd else { return }

        var stroke = live
        let pt = frame.toLogical(location)
        if let last = stroke.last {
            // Always keep the final sample, even if decimation would drop it.
            if pt.x != last.x || pt.y != last.y { stroke.append(pt) }
        } else {
            // Touch down and up without an onChanged (very quick tap).
            stroke = [pt]
        }

        live = []
        strokes.append(stroke)
        emit(PTealSigPath.serialize(strokes), cb)
    }

    private func emit(_ path: String, _ cb: Int) {
        lastSent = path
        if cb != 0 {
            NativeElementBridge.sendTextChangeEvent(cb, nodeId: node.id, text: path)
        }
    }

    // MARK: - Drawing

    private func penColor(_ p: GenericProps) -> Color {
        let dark = colorScheme == .dark
        if dark && p.has("dark_pen_color") {
            return Color(argb: p.getColor("dark_pen_color", default: 0xFFFFFFFF))
        }
        if p.has("pen_color") {
            return Color(argb: p.getColor("pen_color", default: 0xFF000000))
        }
        return dark ? .white : .black
    }

    private func drawStroke(
        _ ctx: GraphicsContext,
        _ pts: [PTealSigPoint],
        _ frame: PTealSigFrame,
        _ color: Color,
        _ style: StrokeStyle
    ) {
        guard let first = pts.first else { return }
        if pts.count == 1 {
            let c = frame.toView(first)
            let r = style.lineWidth / 2
            ctx.fill(
                Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)),
                with: .color(color)
            )
            return
        }
        var path = Path()
        path.move(to: frame.toView(first))
        if pts.count == 2 {
            path.addLine(to: frame.toView(pts[1]))
        } else {
            // Display-only smoothing: quadratic curves through segment midpoints.
            // The exported path keeps the raw points.
            for i in 1..<(pts.count - 1) {
                let c = frame.toView(pts[i])
                let n = frame.toView(pts[i + 1])
                path.addQuadCurve(
                    to: CGPoint(x: (c.x + n.x) / 2, y: (c.y + n.y) / 2),
                    control: c
                )
            }
            path.addLine(to: frame.toView(pts[pts.count - 1]))
        }
        ctx.stroke(path, with: .color(color), style: style)
    }
}

// MARK: - 360x120 <-> view mapping

struct PTealSigPoint {
    var x: CGFloat
    var y: CGFloat
}

let ptealSigW: CGFloat = 360
let ptealSigH: CGFloat = 120

/// Uniform fit (letterbox) of the 360x120 space into a view of width x height.
struct PTealSigFrame {
    let scale: CGFloat
    let ox: CGFloat
    let oy: CGFloat
    let valid: Bool

    init(width: CGFloat, height: CGFloat) {
        let s = min(width / ptealSigW, height / ptealSigH)
        scale = s
        ox = (width - ptealSigW * s) / 2
        oy = (height - ptealSigH * s) / 2
        valid = width > 0 && height > 0 && s > 0 && s.isFinite
    }

    func toLogical(_ p: CGPoint) -> PTealSigPoint {
        PTealSigPoint(
            x: min(max((p.x - ox) / scale, 0), ptealSigW),
            y: min(max((p.y - oy) / scale, 0), ptealSigH)
        )
    }

    func toView(_ p: PTealSigPoint) -> CGPoint {
        CGPoint(x: ox + p.x * scale, y: oy + p.y * scale)
    }
}

// MARK: - SVG path parse / serialize

enum PTealSigPath {

    /// "M 10.5 20 L 11.2 21.4 M 30 5 L 31 6" -> strokes.
    /// Understands M/L (absolute), m/l (relative), H/V/h/v, implicit repeated
    /// coordinate pairs, comma or space separators, and compact forms like
    /// "M10,20L11,21". Z and curve commands are skipped.
    static func parse(_ d: String) -> [[PTealSigPoint]] {
        var strokes: [[PTealSigPoint]] = []
        let b = Array(d.utf8)
        if b.isEmpty { return strokes }

        var cur: [PTealSigPoint] = []
        var cmd: UInt8 = 0x20
        var cx: CGFloat = 0
        var cy: CGFloat = 0
        var args: [CGFloat] = [0, 0]
        var argc = 0

        func need(_ c: UInt8) -> Int {
            switch c {
            case UInt8(ascii: "M"), UInt8(ascii: "m"), UInt8(ascii: "L"), UInt8(ascii: "l"): return 2
            case UInt8(ascii: "H"), UInt8(ascii: "h"), UInt8(ascii: "V"), UInt8(ascii: "v"): return 1
            default: return 0
            }
        }

        func finishStroke() {
            if !cur.isEmpty { strokes.append(cur) }
            cur = []
        }

        func apply() {
            switch cmd {
            case UInt8(ascii: "M"), UInt8(ascii: "m"):
                finishStroke()
                if cmd == UInt8(ascii: "m") { cx += args[0]; cy += args[1] } else { cx = args[0]; cy = args[1] }
                cur = [PTealSigPoint(x: cx, y: cy)]
                // Further pairs after a moveto are implicit lineto.
                cmd = (cmd == UInt8(ascii: "M")) ? UInt8(ascii: "L") : UInt8(ascii: "l")
            case UInt8(ascii: "L"), UInt8(ascii: "l"):
                if cmd == UInt8(ascii: "l") { cx += args[0]; cy += args[1] } else { cx = args[0]; cy = args[1] }
                cur.append(PTealSigPoint(x: cx, y: cy))
            case UInt8(ascii: "H"), UInt8(ascii: "h"):
                cx = (cmd == UInt8(ascii: "h")) ? cx + args[0] : args[0]
                cur.append(PTealSigPoint(x: cx, y: cy))
            case UInt8(ascii: "V"), UInt8(ascii: "v"):
                cy = (cmd == UInt8(ascii: "v")) ? cy + args[0] : args[0]
                cur.append(PTealSigPoint(x: cx, y: cy))
            default:
                break
            }
        }

        func isDigit(_ c: UInt8) -> Bool { c >= 0x30 && c <= 0x39 }
        func isLetter(_ c: UInt8) -> Bool { (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) }

        let n = b.count
        var i = 0
        while i < n {
            let c = b[i]
            if isLetter(c) && c != UInt8(ascii: "e") && c != UInt8(ascii: "E") {
                i += 1
                if c == UInt8(ascii: "Z") || c == UInt8(ascii: "z") {
                    finishStroke()
                    cmd = 0x20
                } else {
                    cmd = c
                }
                argc = 0
            } else if c == UInt8(ascii: "-") || c == UInt8(ascii: "+") || c == UInt8(ascii: ".") || isDigit(c) {
                let start = i
                if c == UInt8(ascii: "-") || c == UInt8(ascii: "+") { i += 1 }
                var seenDot = false
                var digits = 0
                while i < n {
                    let ch = b[i]
                    if isDigit(ch) {
                        digits += 1
                        i += 1
                    } else if ch == UInt8(ascii: ".") && !seenDot {
                        seenDot = true
                        i += 1
                    } else {
                        break
                    }
                }
                if digits == 0 { continue } // lone sign / dot; i already advanced
                if i < n && (b[i] == UInt8(ascii: "e") || b[i] == UInt8(ascii: "E")) {
                    var j = i + 1
                    if j < n && (b[j] == UInt8(ascii: "+") || b[j] == UInt8(ascii: "-")) { j += 1 }
                    let expStart = j
                    while j < n && isDigit(b[j]) { j += 1 }
                    if j > expStart { i = j }
                }
                var text = String(decoding: b[start..<i], as: UTF8.self)
                if text.hasSuffix(".") { text += "0" }
                guard let f = Double(text) else { continue }
                let k = need(cmd)
                if k == 0 { continue }
                args[argc] = CGFloat(f)
                argc += 1
                if argc == k {
                    apply()
                    argc = 0
                }
            } else {
                i += 1 // whitespace, commas, anything unknown
            }
        }
        finishStroke()
        return strokes
    }

    /// strokes -> "M x y L x y ..." in 360x120 space, 1 decimal, space separated.
    static func serialize(_ strokes: [[PTealSigPoint]]) -> String {
        var out = ""
        for stroke in strokes {
            guard let first = stroke.first else { continue }
            if !out.isEmpty { out += " " }

            var px = tenthsOf(first.x)
            var py = tenthsOf(first.y)
            out += "M \(fmt(px)) \(fmt(py))"

            var emitted = 0
            for pt in stroke.dropFirst() {
                let rx = tenthsOf(pt.x)
                let ry = tenthsOf(pt.y)
                if rx == px && ry == py { continue } // identical after rounding
                out += " L \(fmt(rx)) \(fmt(ry))"
                px = rx
                py = ry
                emitted += 1
            }
            if emitted == 0 {
                // A tap: emit a 0.1 long segment so it still renders as a dot.
                out += " L \(fmt(px + 1)) \(fmt(py))"
            }
        }
        return out
    }

    private static func tenthsOf(_ v: CGFloat) -> Int {
        Int((v * 10).rounded())
    }

    /// Tenths -> text without locale involvement (215 -> "21.5", 200 -> "20").
    private static func fmt(_ v: Int) -> String {
        let a = abs(v)
        let sign = v < 0 ? "-" : ""
        let f = a % 10
        return f == 0 ? "\(sign)\(a / 10)" : "\(sign)\(a / 10).\(f)"
    }
}
