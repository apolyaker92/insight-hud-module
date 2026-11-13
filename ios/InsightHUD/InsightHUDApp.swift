import Charts
import CoreLocation
import MapKit
import SwiftUI

@main
struct InsightHUDApp: App {
    var body: some Scene {
        WindowGroup { HUDView() }
    }
}

private struct HUDView: View {
    private enum Tab { case navigation, trips, settings }
    @State private var tab: Tab = .navigation
    @AppStorage("hudAddress") private var address = ""
    @AppStorage("hudPairingCode") private var code = ""
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var locator = CurrentLocation()
    @StateObject private var autocomplete = DestinationAutocomplete()
    @FocusState private var destinationFocused: Bool
    @State private var query = ""
    @State private var results: [MKMapItem] = []
    @State private var selected: MKMapItem?
    @State private var route: MKRoute?
    @State private var connection = ""
    @State private var hudStatus: HUDClient.Status?
    @State private var feedback = ""
    @State private var busy = false
    @State private var discoveredHUDs: [String] = []
    @State private var discoveryMessage = ""
    @State private var discovering = false
    @State private var placeEdit: PlaceEdit?

    private struct PlaceEdit: Identifiable {
        let id = UUID()
        let index: Int
        let place: HUDClient.SavedPlace
    }

    private var client: HUDClient { HUDClient(address: address, code: code) }
    private var paired: Bool { !address.isEmpty && !code.isEmpty }

    var body: some View {
        TabView(selection: $tab) {
            navigation
                .tabItem { Label("Navigation", systemImage: "location") }
                .tag(Tab.navigation)
            NavigationStack {
                if paired {
                    TripHistoryView(client: client)
                        .id(address)
                } else {
                    ContentUnavailableView("Pair your HUD", systemImage: "car",
                                           description: Text("Connect to your HUD in Settings to load trips."))
                        .navigationTitle("Trip History")
                }
            }
            .tabItem { Label("Trip History", systemImage: "chart.bar") }
            .tag(Tab.trips)
            NavigationStack { connectionSettings }
                .tabItem { Label("Settings", systemImage: "gearshape") }
                .tag(Tab.settings)
        }
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            syncSharePairing()
            if paired && !busy { await checkConnection() }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(3)) }
                catch { return }
                if paired && !busy && hudStatus != nil && tab != .trips {
                    await checkConnection(rediscover: false)
                }
            }
        }
        .onChange(of: address) { _, _ in syncSharePairing() }
        .onChange(of: code) { _, _ in syncSharePairing() }
        .sheet(item: $placeEdit) { edit in
            SavedPlaceEditor(place: edit.place) { place in
                guard var places = hudStatus?.saved_places, edit.index <= places.count else { return }
                if edit.index == places.count { places.append(place) }
                else if edit.index < places.count { places[edit.index] = place }
                savePlaces(places)
            }
        }
    }

    private func syncSharePairing() {
        let shared = UserDefaults(suiteName: HUDClient.pairingGroup)
        shared?.set(address, forKey: "hudAddress")
        shared?.set(code, forKey: "hudPairingCode")
    }

    private var navigation: some View {
        NavigationStack {
            Form {
                Section {
                    Text(connection.isEmpty ? "HUD not connected" : connection)
                } header: {
                    Text("Dashboard connection")
                }

                if hudStatus?.navigating == true {
                    Section("HUD controls") {
                        Button("Cancel navigation", role: .destructive) {
                            let target = client
                            run {
                                try await target.cancelRoute()
                                await checkConnection()
                                feedback = "Navigation canceled."
                            }
                        }
                    }
                    .disabled(busy)
                }

                Section("Destination") {
                    TextField("Search for a place or address", text: $query)
                        .focused($destinationFocused)
                        .submitLabel(.search)
                        .onSubmit(search)
                        .onChange(of: query) { _, text in
                            results = []
                            autocomplete.update(text)
                        }
                        .onChange(of: destinationFocused) { _, focused in
                            autocomplete.update(focused ? query : "")
                        }
                    if destinationFocused {
                        ForEach(Array(autocomplete.suggestions.prefix(6)), id: \.self) { suggestion in
                            Button {
                                selectSuggestion(suggestion)
                            } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(suggestion.title)
                                    if !suggestion.subtitle.isEmpty {
                                        Text(suggestion.subtitle).font(.footnote).foregroundStyle(.secondary)
                                    }
                                }
                            }
                            .disabled(busy)
                        }
                    }
                    Button("Search", action: search)
                        .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || busy)
                    ForEach(Array(results.enumerated()), id: \.offset) { _, item in
                        Button {
                            selectDestination(item)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(item.name ?? "Destination")
                                if let detail = item.placemark.title, detail != item.name {
                                    Text(detail).font(.footnote).foregroundStyle(.secondary)
                                }
                            }
                        }
                        .disabled(busy)
                    }
                }

                if let selected {
                    Section(selected.name ?? "Destination") {
                        if let route {
                            let line = route.polyline
                            Map(initialPosition: .rect(line.boundingMapRect.insetBy(dx: -2_000, dy: -2_000))) {
                                MapPolyline(line).stroke(.blue, lineWidth: 5)
                            }
                            .frame(height: 200)
                            Text("\(route.distance / 1609.344, specifier: "%.1f") mi · \(max(1, Int((route.expectedTravelTime / 60).rounded()))) min")
                            Button("Upload directions") {
                                let target = client
                                run {
                                    try await target.sendRoute(route, to: selected)
                                    await checkConnection()
                                    feedback = "Directions sent. Follow the route on your HUD."
                                }
                            }
                            .disabled(!paired || busy)
                        } else {
                            Button("Preview driving directions") { preview(to: selected) }
                                .disabled(busy)
                        }
                        Button("Send destination for offline routing") {
                            let target = client
                            run {
                                try await target.sendDestination(selected)
                                await checkConnection()
                                feedback = "Destination accepted. The HUD is calculating the route."
                            }
                        }
                        .disabled(!paired || busy)
                        if let places = hudStatus?.saved_places {
                            Button("Save to HUD") {
                                placeEdit = PlaceEdit(index: places.count, place: HUDClient.SavedPlace(selected))
                            }
                            .disabled(busy)
                        }
                    }
                }
                if busy || !feedback.isEmpty {
                    Section {
                        if busy { ProgressView() }
                        if !feedback.isEmpty { Text(feedback) }
                    }
                }
            }
            .navigationTitle("Navigation")
        }
    }

    private var connectionSettings: some View {
        Form {
            Section {
                Button("Find nearby HUDs") { Task { await findHUDs() } }
                    .disabled(discovering)
                if discovering { ProgressView("Looking for the HUD") }
                ForEach(discoveredHUDs, id: \.self) { host in
                    Button {
                        address = host
                        clearConnection()
                    } label: {
                        LabeledContent(host, value: address == host ? "Selected" : "")
                    }
                }
                if !discoveryMessage.isEmpty { Text(discoveryMessage) }
                SecureField("Pairing code", text: $code)
                    .keyboardType(.asciiCapable)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: code) { _, _ in clearConnection() }
                Button("Check connection") { run { await checkConnection() } }
                    .disabled(!paired || busy)
                if !connection.isEmpty { Text(connection) }
            } footer: {
                Text("Join the Android HUD hotspot and get the pairing code from BT SETUP → Navigation.")
            }
            Section("Manual connection") {
                TextField("HUD address", text: $address)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onChange(of: address) { _, _ in clearConnection() }
            }
            if let view = hudStatus?.view, let mirror = hudStatus?.mirror,
               let brightness = hudStatus?.brightness {
                Section("HUD display") {
                    Picker("View", selection: Binding(get: { view }, set: { updateDisplay(.init(view: $0)) })) {
                        Text("Gauges").tag("gauges")
                        Text("Split map").tag("split")
                        Text("Full map").tag("full")
                    }
                    Toggle("Mirror", isOn: Binding(get: { mirror }, set: { updateDisplay(.init(mirror: $0)) }))
                    Picker("Brightness", selection: Binding(get: { brightness }, set: { updateDisplay(.init(brightness: $0)) })) {
                        Text("Auto").tag(0)
                        Text("High").tag(1)
                        Text("Medium").tag(2)
                        Text("Low").tag(3)
                    }
                }
                .disabled(busy)
            }
            if let places = hudStatus?.saved_places {
                Section {
                    if places.isEmpty { Text("Save a destination from the Navigation tab.") }
                    if places.count > 1 {
                        NavigationLink("Reorder saved places") {
                            SavedPlaceOrder(places: places, onSave: savePlaces)
                        }
                    }
                    ForEach(places.indices, id: \.self) { index in
                        Button {
                            placeEdit = PlaceEdit(index: index, place: places[index])
                        } label: {
                            Label(places[index].name, systemImage: (places[index].icon ?? .pin).symbol)
                        }
                            .swipeActions {
                                Button("Remove", role: .destructive) {
                                    var updated = places
                                    updated.remove(at: index)
                                    savePlaces(updated)
                                }
                            }
                    }
                } header: {
                    Text("Saved places")
                } footer: {
                    Text("On the HUD, press F4 to open and cycle, F1 to go, or F2 to close.")
                }
                .disabled(busy)
            }
            if busy || !feedback.isEmpty {
                Section {
                    if busy { ProgressView() }
                    if !feedback.isEmpty { Text(feedback) }
                }
            }
        }
        .navigationTitle("Settings")
        .task {
            if address.isEmpty { await findHUDs() }
        }
    }

    private func findHUDs() async {
        guard !discovering else { return }
        discovering = true
        discoveryMessage = ""
        defer { discovering = false }
        discoveredHUDs = await HUDDiscovery().find().sorted()
        if discoveredHUDs.isEmpty {
            discoveryMessage = "No HUD found. Make sure the HUD is running and both phones are on the same Wi-Fi."
        } else if address.isEmpty && discoveredHUDs.count == 1 {
            address = discoveredHUDs[0]
        }
    }

    private func search() {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return }
        destinationFocused = false
        run {
            results = []
            let request = MKLocalSearch.Request()
            request.naturalLanguageQuery = term
            results = try await MKLocalSearch(request: request).start().mapItems
            if results.isEmpty { feedback = "No places found." }
        }
    }

    private func selectSuggestion(_ suggestion: MKLocalSearchCompletion) {
        destinationFocused = false
        run {
            let request = MKLocalSearch.Request(completion: suggestion)
            guard let item = try await MKLocalSearch(request: request).start().mapItems.first else {
                throw HUDError("That destination couldn’t be found.")
            }
            selectDestination(item)
        }
    }

    private func selectDestination(_ item: MKMapItem) {
        destinationFocused = false
        selected = item
        route = nil
        results = []
        feedback = ""
    }

    private func preview(to item: MKMapItem) {
        run {
            let coordinate = try await locator.locate()
            let request = MKDirections.Request()
            request.source = MKMapItem(placemark: MKPlacemark(coordinate: coordinate))
            request.destination = item
            request.transportType = .automobile
            guard let result = try await MKDirections(request: request).calculate().routes.first else {
                throw HUDError("No driving route was found.")
            }
            route = result
        }
    }

    private func clearConnection() {
        connection = ""
        hudStatus = nil
    }

    private func savePlaces(_ places: [HUDClient.SavedPlace]) {
        let target = client
        run {
            try await target.setSavedPlaces(places)
            await checkConnection()
            feedback = "Saved places updated on the HUD."
        }
    }

    private func updateDisplay(_ settings: HUDClient.DisplaySettings) {
        let target = client
        run {
            try await target.setDisplay(settings)
            await checkConnection()
        }
    }

    private func checkConnection(rediscover: Bool = true) async {
        let target = client
        do {
            let status = try await target.status()
            guard !Task.isCancelled, target.address == address, target.code == code else { return }
            hudStatus = status
            connection = status.navigating
                ? "Connected · \(status.destination ?? "Route active")"
                : "Connected"
        } catch {
            guard !Task.isCancelled, target.address == address, target.code == code else { return }
            hudStatus = nil
            if rediscover && error is URLError {
                let candidates = await HUDDiscovery().find()
                for candidate in candidates where candidate != target.address {
                    let discovered = HUDClient(address: candidate, code: target.code)
                    if let status = try? await discovered.status() {
                        guard !Task.isCancelled, target.address == address, target.code == code else { return }
                        address = candidate
                        hudStatus = status
                        connection = status.navigating ? "Connected · \(status.destination ?? "Route active")" : "Connected"
                        return
                    }
                }
            }
            guard !Task.isCancelled, target.address == address, target.code == code else { return }
            connection = "Couldn’t connect. \(error.localizedDescription)"
        }
    }

    private func run(_ action: @escaping @MainActor () async throws -> Void) {
        guard !busy else { return }
        busy = true
        feedback = ""
        Task { @MainActor in
            defer { busy = false }
            do { try await action() }
            catch { feedback = error.localizedDescription }
        }
    }
}

private struct SavedPlaceOrder: View {
    @Environment(\.dismiss) private var dismiss
    @State var places: [HUDClient.SavedPlace]
    let onSave: ([HUDClient.SavedPlace]) -> Void

    var body: some View {
        List {
            ForEach(places.indices, id: \.self) { index in
                let place = places[index]
                Label(place.name, systemImage: (place.icon ?? .pin).symbol)
            }
            .onMove { places.move(fromOffsets: $0, toOffset: $1) }
        }
        .environment(\.editMode, .constant(.active))
        .navigationTitle("Reorder Saved Places")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") {
                    onSave(places)
                    dismiss()
                }
            }
        }
    }
}

private struct SavedPlaceEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var icon: HUDClient.SavedPlace.Icon
    let place: HUDClient.SavedPlace
    let onSave: (HUDClient.SavedPlace) -> Void

    init(place: HUDClient.SavedPlace, onSave: @escaping (HUDClient.SavedPlace) -> Void) {
        self.place = place
        self.onSave = onSave
        _name = State(initialValue: place.name)
        _icon = State(initialValue: place.icon ?? .pin)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("Place name", text: $name)
                Picker("Map icon", selection: $icon) {
                    ForEach(HUDClient.SavedPlace.Icon.allCases, id: \.self) { icon in
                        Label(icon.rawValue.capitalized, systemImage: icon.symbol).tag(icon)
                    }
                }
            }
            .navigationTitle("Saved Place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(.init(name: trimmedName, lat: place.lat, lon: place.lon, icon: icon))
                        dismiss()
                    }
                    .disabled(trimmedName.isEmpty || trimmedName.utf16.count > 80)
                }
            }
        }
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }
}

struct HUDTrip: Decodable {
    let ended: Int64
    let seconds: Int
    let miles: Double?
    let mpg: Double?
    let rpm: Double?
    let coolant: Double?

    var date: Date { Date(timeIntervalSince1970: Double(ended) / 1000) }
}

private struct TripHistoryView: View {
    private enum Metric: String, CaseIterable, Identifiable {
        case distance = "Distance", economy = "Fuel economy", duration = "Duration"

        var id: Self { self }
        var unit: String {
            switch self {
            case .distance: return "mi"
            case .economy: return "mpg"
            case .duration: return "min"
            }
        }

        func value(for trip: HUDTrip) -> Double? {
            switch self {
            case .distance: return trip.miles
            case .economy: return trip.mpg
            case .duration: return Double(trip.seconds) / 60
            }
        }
    }

    let client: HUDClient
    @State private var trips: [HUDTrip] = []
    @State private var metric: Metric = .distance
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        List {
            Section {
                NavigationLink {
                    DriveMapView(client: client)
                } label: {
                    Label("Everywhere I’ve driven", systemImage: "map")
                        .font(.headline)
                }
            }
            if loading && trips.isEmpty {
                ProgressView("Loading trips")
            } else if let error {
                Text(error)
                Button("Retry") { Task { await loadTrips() } }
            } else if trips.isEmpty {
                Text("Completed drives with live OBD data will appear here.")
            }

            if !trips.isEmpty {
                Section("At a glance") {
                    HStack {
                        stat("\(trips.count)", "Drives")
                        stat(trips.compactMap(\.miles).isEmpty ? "—" :
                             trips.compactMap(\.miles).reduce(0, +).formatted(.number.precision(.fractionLength(1))),
                             "Est. miles")
                        stat("\(trips.map(\.seconds).reduce(0, +) / 60)", "Minutes")
                    }
                }
                Section("Recent trips") {
                    Picker("Chart", selection: $metric) {
                        ForEach(Metric.allCases) { Text($0.rawValue).tag($0) }
                    }
                    if trips.contains(where: { metric.value(for: $0) != nil }) {
                        Chart(Array(trips.reversed().enumerated()), id: \.offset) { index, trip in
                            if let value = metric.value(for: trip) {
                                BarMark(x: .value("Trip", index + 1), y: .value(metric.unit, value))
                                    .accessibilityLabel(trip.date.formatted(date: .abbreviated, time: .shortened))
                                    .accessibilityValue("\(value.formatted(.number.precision(.fractionLength(1)))) \(metric.unit)")
                                    .foregroundStyle(.teal.gradient)
                                    .cornerRadius(4)
                            }
                        }
                        .chartXAxisLabel("Trip, oldest to newest")
                        .chartYAxisLabel(metric.unit)
                        .frame(height: 180)
                    } else {
                        Text("No \(metric.rawValue.lowercased()) readings in these trips.")
                    }
                }
                Section("Trip summaries") {
                    ForEach(Array(trips.enumerated()), id: \.offset) { _, trip in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(trip.date.formatted(date: .abbreviated, time: .shortened))
                                .font(.headline)
                            Text(summary(trip))
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("Trip History")
        .task { await loadTrips() }
        .refreshable { await loadTrips() }
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.title2.weight(.semibold)).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
    }

    private func summary(_ trip: HUDTrip) -> String {
        var values = [Duration.seconds(trip.seconds).formatted(.units(allowed: [.hours, .minutes, .seconds]))]
        if let miles = trip.miles { values.append("\(miles.formatted(.number.precision(.fractionLength(1)))) est. mi") }
        if let mpg = trip.mpg { values.append("\(mpg.formatted(.number.precision(.fractionLength(0)))) est. mpg") }
        if let rpm = trip.rpm { values.append("\(Int(rpm.rounded())) peak RPM") }
        if let coolant = trip.coolant { values.append("\(Int(coolant.rounded())) °F peak coolant") }
        return values.joined(separator: " · ")
    }

    private func loadTrips() async {
        loading = true
        defer { loading = false }
        do {
            trips = try await client.trips()
            error = nil
        } catch {
            if !Task.isCancelled { self.error = error.localizedDescription }
        }
    }
}

private struct DriveMapView: View {
    let client: HUDClient
    @State private var lines: [MKPolyline] = []
    @State private var position: MapCameraPosition = .automatic
    @State private var message: String?
    @State private var progress: Double?

    var body: some View {
        Group {
            if let progress {
                ProgressView("Loading recorded drives", value: progress)
                    .padding(24)
            } else if let message {
                ContentUnavailableView {
                    Label("Drive map", systemImage: "map")
                } description: {
                    Text(message)
                } actions: {
                    Button("Reload") { Task { await load() } }
                }
            } else {
                Map(position: $position) {
                    ForEach(lines.indices, id: \.self) { index in
                        MapPolyline(lines[index]).stroke(.teal.opacity(0.72), lineWidth: 3)
                    }
                }
                .mapControls { MapCompass(); MapScaleView() }
            }
        }
        .navigationTitle("Drive Map")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        guard progress == nil else { return }
        progress = 0
        defer { progress = nil }
        message = nil
        do {
            let ids = try await client.driveIDs()
            var polylines: [MKPolyline] = []
            for (index, id) in ids.enumerated() {
                try Task.checkCancellation()
                for line in try await client.driveLines(id) {
                    let coordinates = line.compactMap { point -> CLLocationCoordinate2D? in
                        guard point.count == 2 else { return nil }
                        let coordinate = CLLocationCoordinate2D(latitude: point[0], longitude: point[1])
                        return CLLocationCoordinate2DIsValid(coordinate) ? coordinate : nil
                    }
                    if coordinates.count > 1 {
                        polylines.append(MKPolyline(coordinates: coordinates, count: coordinates.count))
                    }
                }
                progress = Double(index + 1) / Double(ids.count)
            }
            lines = polylines
            if lines.isEmpty {
                message = "No recorded drives with GPS coverage yet."
            } else {
                let bounds = lines.reduce(MKMapRect.null) { $0.union($1.boundingMapRect) }
                position = .rect(bounds.insetBy(dx: -max(800, bounds.width * 0.1),
                                                dy: -max(800, bounds.height * 0.1)))
            }
        } catch {
            if !Task.isCancelled { message = error.localizedDescription }
        }
    }
}

@MainActor
private final class DestinationAutocomplete: NSObject, ObservableObject, @preconcurrency MKLocalSearchCompleterDelegate {
    @Published private(set) var suggestions: [MKLocalSearchCompletion] = []
    private let completer = MKLocalSearchCompleter()
    private var active = false

    override init() {
        super.init()
        completer.delegate = self
        completer.resultTypes = [.address, .pointOfInterest]
    }

    func update(_ text: String) {
        let query = text.trimmingCharacters(in: .whitespacesAndNewlines)
        active = query.count >= 2
        suggestions = []
        if active { completer.queryFragment = query }
        else {
            completer.cancel()
            completer.queryFragment = ""
        }
    }

    func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        if active { suggestions = completer.results }
    }

    func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        suggestions = []
    }
}

@MainActor
private final class CurrentLocation: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var pending: CheckedContinuation<CLLocationCoordinate2D, Error>?

    func locate() async throws -> CLLocationCoordinate2D {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        return try await withCheckedThrowingContinuation { continuation in
            pending = continuation
            updateAuthorization()
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        updateAuthorization()
    }

    private func updateAuthorization() {
        guard pending != nil else { return }
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse: manager.requestLocation()
        default: finish(.failure(HUDError("Allow location access to preview directions from here.")))
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let coordinate = locations.last?.coordinate { finish(.success(coordinate)) }
        else { finish(.failure(HUDError("No current location was available."))) }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        finish(.failure(error))
    }

    private func finish(_ result: Result<CLLocationCoordinate2D, Error>) {
        let continuation = pending
        pending = nil
        continuation?.resume(with: result)
    }
}
