import Foundation
import MapKit
import UIKit

struct HUDClient {
    struct Status: Decodable {
        let app: String
        let navigating: Bool
        let destination: String?
        let view: String?
        let mirror: Bool?
        let brightness: Int?
        let saved_places: [SavedPlace]?
    }

    struct DisplaySettings: Encodable {
        var view: String? = nil
        var mirror: Bool? = nil
        var brightness: Int? = nil
    }

    private struct Reply: Decodable {
        let ok: Bool
        let error: String?
    }

    struct SavedPlace: Codable {
        enum Icon: String, CaseIterable, Codable {
            case pin, home, work, star, fuel, food
            var symbol: String {
                switch self {
                case .pin: return "mappin.circle.fill"
                case .home: return "house.fill"
                case .work: return "briefcase.fill"
                case .star: return "star.fill"
                case .fuel: return "fuelpump.fill"
                case .food: return "fork.knife"
                }
            }
        }
        let name: String
        let lat: Double
        let lon: Double
        var icon: Icon?

        init(_ item: MKMapItem) {
            name = String((item.name ?? "Destination").prefix(40))
            lat = item.placemark.coordinate.latitude
            lon = item.placemark.coordinate.longitude
        }

        init(name: String, lat: Double, lon: Double, icon: Icon) {
            self.name = name
            self.lat = lat
            self.lon = lon
            self.icon = icon
        }
    }

    private struct Directions: Encodable {
        struct Step: Encodable {
            let instruction: String
            let location: [Double]
        }

        let name: String
        let duration_s: TimeInterval
        let points: [[Double]]
        let steps: [Step]
    }

    let address: String
    let code: String

    func driveIDs() async throws -> [String] {
        struct Index: Decodable { let drives: [String] }
        return try JSONDecoder().decode(Index.self, from: await request("tracks")).drives
    }

    func driveLines(_ id: String) async throws -> [[[Double]]] {
        struct Track: Decodable { let lines: [[[Double]]] }
        return try JSONDecoder().decode(Track.self, from: await request("tracks", driveID: id)).lines
    }

    #if !HUD_SHARE_EXTENSION
    func trips() async throws -> [HUDTrip] {
        struct History: Decodable { let trips: [HUDTrip] }
        return try await withThrowingTaskGroup(of: [HUDTrip].self) { group in
            group.addTask {
                try JSONDecoder().decode(History.self, from: await request("trips")).trips
            }
            group.addTask {
                try await Task.sleep(for: .seconds(8))
                throw HUDError("Trip history timed out. Check the HUD connection and try again.")
            }
            defer { group.cancelAll() }
            return try await group.next() ?? []
        }
    }
    #endif

    func cancelRoute() async throws {
        try await send("cancel", [String: String]())
    }

    func setDisplay(_ settings: DisplaySettings) async throws {
        try await send("settings", settings)
    }

    func status() async throws -> Status {
        let data = try await request("status")
        let status = try JSONDecoder().decode(Status.self, from: data)
        guard status.app == "insight-hud" else {
            throw HUDError("The address did not return Insight HUD status.")
        }
        return status
    }

    func sendDestination(_ item: MKMapItem) async throws {
        try await send("destination", SavedPlace(item))
    }

    func setSavedPlaces(_ places: [SavedPlace]) async throws {
        try await send("settings", ["saved_places": places])
    }

    static let pairingGroup = "group.com.andrew.insighthud.companion"

    func sendRoute(_ route: MKRoute, to item: MKMapItem) async throws {
        let points = coordinates(route.polyline)
        guard points.count >= 2 else { throw HUDError("The route has no usable map line.") }
        let steps = route.steps.compactMap { step -> Directions.Step? in
            guard !step.instructions.isEmpty,
                  let start = coordinates(step.polyline).first else { return nil }
            return Directions.Step(instruction: step.instructions, location: start)
        }
        try await send("route", Directions(name: item.name ?? "Destination",
                                         duration_s: route.expectedTravelTime, points: points, steps: steps))
    }

    private func coordinates(_ line: MKPolyline) -> [[Double]] {
        guard line.pointCount > 0 else { return [] }
        let points = line.points()
        return (0..<line.pointCount).map { index in
            let point = points[index].coordinate
            return [point.latitude, point.longitude]
        }
    }

    @MainActor
    private func send(_ path: String, _ payload: some Encodable) async throws {
        let body = try JSONEncoder().encode(payload)
        guard body.count <= 4 * 1024 * 1024 else { throw HUDError("This route exceeds the HUD’s upload limit.") }
        #if HUD_SHARE_EXTENSION
        let data = try await request(path, body: body)
        #else
        let upload = Task { try await request(path, body: body) }
        let background = UIApplication.shared.beginBackgroundTask(withName: "Send to Insight HUD") {
            upload.cancel()
        }
        defer {
            if background != .invalid { UIApplication.shared.endBackgroundTask(background) }
        }
        let data = try await upload.value
        #endif
        let reply = try JSONDecoder().decode(Reply.self, from: data)
        guard reply.ok else { throw HUDError(reply.error ?? "The HUD rejected the request.") }
    }

    private func request(_ path: String, body: Data? = nil, driveID: String? = nil) async throws -> Data {
        let input = address.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { throw HUDError("Enter the pairing code shown on the HUD.") }
        guard var parts = URLComponents(string: input.contains("://") ? input : "http://\(input)"),
              parts.scheme == "http", let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/" else {
            throw HUDError("Enter the address shown on the HUD under BT SETUP → Navigation.")
        }
        parts.port = parts.port ?? 8765
        parts.path = "/\(path)"
        if let driveID { parts.queryItems = [URLQueryItem(name: "id", value: driveID)] }
        guard let url = parts.url else { throw HUDError("The HUD address is invalid.") }
        var request = URLRequest(url: url, timeoutInterval: 8)
        request.setValue(token, forHTTPHeaderField: "X-HUD-Token")
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let (data, response) = try await URLSession.shared.data(for: request, delegate: NoRedirects())
        guard let response = response as? HTTPURLResponse else { throw HUDError("The HUD did not return HTTP status.") }
        guard (200..<300).contains(response.statusCode) else {
            let message = try? JSONDecoder().decode(Reply.self, from: data).error
            throw HUDError(message ?? "HUD returned HTTP \(response.statusCode).")
        }
        return data
    }
}

struct HUDError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

@MainActor
final class HUDDiscovery: NSObject, @preconcurrency NetServiceBrowserDelegate, @preconcurrency NetServiceDelegate {
    private let browser = NetServiceBrowser()
    private var services: [NetService] = []
    private var addresses: [String] = []
    private var pending: CheckedContinuation<[String], Never>?

    func find() async -> [String] {
        await withCheckedContinuation { continuation in
            pending = continuation
            browser.delegate = self
            browser.searchForServices(ofType: "_insighthud._tcp.", inDomain: "local.")
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(3))
                finish()
            }
        }
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        services.append(service)
        service.delegate = self
        service.resolve(withTimeout: 2)
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let name = sender.hostName, sender.port > 0 else { return }
        let host = name.hasSuffix(".") ? String(name.dropLast()) : name
        let address = "\(host):\(sender.port)"
        if !addresses.contains(address) { addresses.append(address) }
    }

    private func finish() {
        browser.stop()
        for service in services { service.stop() }
        pending?.resume(returning: addresses)
        pending = nil
    }
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest) async -> URLRequest? { nil }
}
