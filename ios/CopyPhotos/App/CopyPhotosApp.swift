import CopyCore
import SwiftUI

@main
struct CopyPhotosApp: App {
    @StateObject private var photos: PhotosController
    @StateObject private var destination: DestinationController
    @StateObject private var details: PhotoDetailsController
    private let environment: AppEnvironment
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let env = AppEnvironment()
        env.background.register(service: env.service, destination: env.destination)
        environment = env
        _photos = StateObject(wrappedValue: PhotosController(service: env.service, jobs: env.jobs,
                                                             destination: env.destination, library: env.library))
        _destination = StateObject(wrappedValue: DestinationController(destination: env.destination,
                                                                       background: env.background))
        _details = StateObject(wrappedValue: PhotoDetailsController(service: env.service, loader: env.photoInfo))
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(photos)
                .environmentObject(destination)
                .environmentObject(details)
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: photos.onAppActive()
            case .background: environment.background.schedule(for: environment.destination)
            default: break
            }
        }
    }
}

struct RootView: View {
    var body: some View {
        TabView {
            NavigationStack { PhotosView() }
                .tabItem { Label("Photos", systemImage: "photo.on.rectangle") }
            NavigationStack { DestinationView() }
                .tabItem { Label("Destination", systemImage: "externaldrive.connected.to.line.below") }
        }
    }
}
