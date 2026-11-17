import SwiftUI
import UIKit

final class ImageLoader {
    static let shared = ImageLoader()

    private let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 200 * 1024 * 1024
        return c
    }()

    func cached(_ url: String) -> UIImage? { cache.object(forKey: url as NSString) }

    func image(url: String, headers: [String: String] = [:]) async throws -> UIImage {
        if let hit = cached(url) { return hit }
        let data: Data
        if url.hasPrefix("file://"), let fileURL = URL(string: url) {
            data = try Data(contentsOf: fileURL)
        } else {
            data = try await HTTPClient.shared.request(url, headers: headers).0
        }
        guard let image = UIImage(data: data) else { throw SourceError.parse("immagine") }
        let prepared = await image.byPreparingForDisplay() ?? image
        let cost = Int(prepared.size.width * prepared.size.height * prepared.scale * prepared.scale * 4)
        cache.setObject(prepared, forKey: url as NSString, cost: cost)
        return prepared
    }

    func clearMemory() { cache.removeAllObjects() }

    static func clearDiskCache() {
        HTTPClient.shared.session.configuration.urlCache?.removeAllCachedResponses()
        URLCache.shared.removeAllCachedResponses()
    }

    static var diskCacheSize: Int {
        HTTPClient.shared.session.configuration.urlCache?.currentDiskUsage ?? 0
    }
}

/// Immagine remota con header personalizzati (Referer ecc.), che AsyncImage non permette.
struct RemoteImage: View {
    let url: String?
    var headers: [String: String] = [:]
    var contentMode: ContentMode = .fill

    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else if failed {
                Color.secondary.opacity(0.15)
                Image(systemName: "photo").foregroundStyle(.secondary)
            } else {
                Color.secondary.opacity(0.12)
            }
        }
        .task(id: url) {
            guard let url, !url.isEmpty else { failed = true; return }
            if let hit = ImageLoader.shared.cached(url) { image = hit; return }
            do {
                image = try await ImageLoader.shared.image(url: url, headers: headers)
                failed = false
            } catch {
                failed = true
            }
        }
    }
}

/// Copertina in stile Mihon: angoli arrotondati, proporzione 2:3.
struct CoverImage: View {
    let url: String?
    var headers: [String: String] = [:]

    var body: some View {
        Color.clear
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .overlay(RemoteImage(url: url, headers: headers))
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }
}

/// Pagina zoomabile (pinch e doppio tocco) per il lettore paginato.
struct ZoomableImage: UIViewRepresentable {
    let image: UIImage
    var onTap: (CGPoint, CGSize) -> Void

    func makeUIView(context: Context) -> ZoomScrollView {
        let view = ZoomScrollView()
        view.onTap = onTap
        view.setImage(image)
        return view
    }

    func updateUIView(_ view: ZoomScrollView, context: Context) {
        view.onTap = onTap
        if view.imageView.image !== image { view.setImage(image) }
    }
}

final class ZoomScrollView: UIScrollView, UIScrollViewDelegate {
    let imageView = UIImageView()
    var onTap: ((CGPoint, CGSize) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        delegate = self
        minimumZoomScale = 1
        maximumZoomScale = 5
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        bouncesZoom = true
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        addSubview(imageView)

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        addGestureRecognizer(doubleTap)
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap(_:)))
        singleTap.require(toFail: doubleTap)
        addGestureRecognizer(singleTap)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) non supportato") }

    func setImage(_ image: UIImage) {
        imageView.image = image
        zoomScale = 1
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if zoomScale <= minimumZoomScale + 0.001 {
            imageView.frame = bounds
            contentSize = bounds.size
        }
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        if zoomScale > minimumZoomScale + 0.01 {
            setZoomScale(minimumZoomScale, animated: true)
        } else {
            let p = gesture.location(in: imageView)
            let w = bounds.width / 2.5
            let h = bounds.height / 2.5
            zoom(to: CGRect(x: p.x - w / 2, y: p.y - h / 2, width: w, height: h), animated: true)
        }
    }

    @objc private func handleSingleTap(_ gesture: UITapGestureRecognizer) {
        let p = gesture.location(in: self)
        onTap?(CGPoint(x: p.x - contentOffset.x, y: p.y - contentOffset.y), bounds.size)
    }
}
