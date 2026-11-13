import MapKit
import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private let message = UILabel()
    private var upload: Task<Void, Never>?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        message.numberOfLines = 0
        message.textAlignment = .center
        message.font = .preferredFont(forTextStyle: .body)
        message.text = "Sending to Insight HUD…"
        let close = UIButton(type: .system)
        close.setTitle("Done", for: .normal)
        close.addTarget(self, action: #selector(finish), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [message, close])
        stack.axis = .vertical
        stack.spacing = 20
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.safeAreaLayoutGuide.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24)
        ])
        upload = Task { [weak self] in
            guard let self else { return }
            do {
                message.text = try await send()
            } catch {
                if !Task.isCancelled { message.text = error.localizedDescription }
            }
        }
    }

    @objc private func finish() {
        upload?.cancel()
        extensionContext?.completeRequest(returningItems: nil)
    }

    private func send() async throws -> String {
        let pairing = UserDefaults(suiteName: HUDClient.pairingGroup)
        guard let address = pairing?.string(forKey: "hudAddress"), !address.isEmpty,
              let code = pairing?.string(forKey: "hudPairingCode"), !code.isEmpty else {
            throw HUDError("Open Insight HUD and pair your dashboard in Settings first.")
        }
        let saved = HUDClient(address: address, code: code)
        let client: HUDClient
        do {
            _ = try await saved.status()
            client = saved
        } catch let error as URLError {
            var found: HUDClient?
            for address in await HUDDiscovery().find() where address != saved.address {
                let candidate = HUDClient(address: address, code: code)
                if (try? await candidate.status()) != nil { found = candidate; break }
            }
            guard let found else { throw error }
            client = found
        }
        let link = try await mapLink()
        let destination = try await mapItem(link.destination)
        let route: MKRoute?
        do {
            let request = MKDirections.Request()
            if let origin = link.origin, !origin.isEmpty, origin != "Current Location" {
                request.source = try await mapItem(origin)
            } else {
                request.source = MKMapItem.forCurrentLocation()
            }
            request.destination = destination
            request.transportType = .automobile
            route = try await MKDirections(request: request).calculate().routes.first
        } catch {
            try Task.checkCancellation()
            route = nil
        }
        try Task.checkCancellation()
        if let route {
            try await client.sendRoute(route, to: destination)
            return "Directions sent to your HUD."
        }
        try await client.sendDestination(destination)
        return "Destination sent. The HUD is calculating the route."
    }

    private func mapLink() async throws -> (destination: String, origin: String?) {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        for provider in providers {
            for type in [UTType.url, .plainText] where provider.hasItemConformingToTypeIdentifier(type.identifier) {
                guard let item = try? await provider.loadItem(forTypeIdentifier: type.identifier) else { continue }
                let value = (item as? URL)?.absoluteString ?? (item as? String) ?? ""
                let detector = try NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
                for match in detector.matches(in: value, range: NSRange(value.startIndex..., in: value)) {
                    guard let url = match.url, let host = url.host?.lowercased(),
                          ["http", "https"].contains(url.scheme) else { continue }
                    let expanded: URL
                    if ["maps.app.goo.gl", "goo.gl", "maps.apple", "apple.co"].contains(host) {
                        var request = URLRequest(url: url, timeoutInterval: 8)
                        request.httpMethod = "GET"
                        let (_, response) = try await URLSession.shared.data(for: request)
                        expanded = response.url ?? url
                    } else {
                        expanded = url
                    }
                    if let destination = parse(expanded) { return destination }
                }
            }
        }
        throw HUDError("Share a place, dropped pin, or driving directions from Apple Maps or Google Maps.")
    }

    private func parse(_ url: URL) -> (destination: String, origin: String?)? {
        guard let host = url.host?.lowercased(),
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        func value(_ key: String) -> String? {
            parts.queryItems?.first(where: { $0.name == key })?.value?.nilIfBlank
        }
        if host == "maps.apple.com" {
            guard let destination = value("destination") ?? value("daddr") ?? value("coordinate")
                ?? value("ll") ?? value("address") ?? value("q") ?? value("query") else { return nil }
            return (destination, value("source") ?? value("saddr"))
        }
        guard host == "maps.google.com" || host == "www.google.com" || host == "google.com" else { return nil }
        if let destination = value("destination") ?? value("daddr") ?? value("query") ?? value("q") {
            return (destination, value("origin") ?? value("saddr"))
        }
        let path = parts.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false)
            .map { String($0).removingPercentEncoding ?? String($0) }
        if let index = path.firstIndex(of: "dir"), path.count > index + 2,
           let destination = path[index + 2].nilIfBlank, !destination.hasPrefix("@") {
            return (destination, path[index + 1].nilIfBlank)
        }
        if let index = path.firstIndex(of: "place"), path.count > index + 1 {
            if let data = path.first(where: { $0.hasPrefix("data=") }),
               let match = data.range(of: #"!3d(-?[0-9.]+)!4d(-?[0-9.]+)"#, options: .regularExpression) {
                let coordinates = data[match].dropFirst(3).replacingOccurrences(of: "!4d", with: ",")
                return (coordinates, nil)
            }
            if let name = path[index + 1].nilIfBlank { return (name, nil) }
        }
        return nil
    }

    private func mapItem(_ value: String) async throws -> MKMapItem {
        let fields = value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        if fields.count == 2, let latitude = Double(fields[0]), let longitude = Double(fields[1]) {
            let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
            if CLLocationCoordinate2DIsValid(coordinate) {
                return MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
            }
        }
        let request = MKLocalSearch.Request()
        request.naturalLanguageQuery = value
        guard let result = try await MKLocalSearch(request: request).start().mapItems.first else {
            throw HUDError("The shared destination couldn’t be found.")
        }
        return result
    }
}

private extension String {
    var nilIfBlank: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
