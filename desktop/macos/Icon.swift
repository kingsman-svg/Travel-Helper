import AppKit
let folder = CommandLine.arguments[1]
try FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true)
for size in [16,32,128,256,512] {
    for scale in [1,2] {
        let pixels = size * scale
        let image = NSImage(size: NSSize(width: pixels, height: pixels))
        image.lockFocus()
        let ctx = NSGraphicsContext.current!.cgContext
        ctx.scaleBy(x: CGFloat(pixels)/1024, y: CGFloat(pixels)/1024)
        NSColor(calibratedRed: 0.20, green: 0.36, blue: 0.31, alpha: 1).setFill()
        NSBezierPath(roundedRect: NSRect(x: 40,y: 40,width: 944,height: 944), xRadius: 210,yRadius: 210).fill()
        NSColor(calibratedRed: 0.97,green: 0.97,blue: 0.90,alpha: 1).setStroke()
        let path = NSBezierPath(); path.lineWidth = 26;path.lineJoinStyle = .round
        path.move(to: NSPoint(x: 250,y: 300));path.line(to:NSPoint(x:512,y:760));path.line(to:NSPoint(x:774,y:300));path.close()
        path.move(to:NSPoint(x:422,y:602));path.line(to:NSPoint(x:512,y:510));path.line(to:NSPoint(x:574,y:565))
        path.move(to:NSPoint(x:334,y:388));path.line(to:NSPoint(x:690,y:388));path.stroke()
        image.unlockFocus()
        let bitmap=NSBitmapImageRep(data:image.tiffRepresentation!)!
        try bitmap.representation(using:.png,properties:[:])!.write(to:URL(fileURLWithPath:folder+"/icon_\(size)x\(size)\(scale==2 ? "@2x" : "").png"))
    }
}
