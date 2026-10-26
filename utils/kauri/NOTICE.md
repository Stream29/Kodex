# Kauri-derived Native primitives

- Resource reading and system WebView window code derive from the user's `Kauri/framework` at commit `5829d2c9eceee05f3fa36eb896fc3dd8acb3f7d1`. The deterministic resource packer derives from that repository's `scripts/`.
- Kodex adaptations include spec/actual API placement, Gradle integration, portable resource tests and lazy Linux GTK/WebKit loading. The Kauri application and its service lifetime are not included.
- Microsoft WebView2 SDK `1.0.3650.58` declaration headers are retained verbatim in source. Their redistribution notice is included as `LICENSE-WebView2-SDK.txt`.
- System browser/WebView engines are not bundled by these primitives.
