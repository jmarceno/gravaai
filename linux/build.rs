use cxx_qt_build::{CxxQtBuilder, QmlFile, QmlModule};

fn main() {
    // The daemon/core binary is intentionally built without Qt. Cargo still
    // executes build.rs for every target, so only generate the CXX-Qt bridge
    // when the companion UI feature is enabled.
    if std::env::var_os("CARGO_FEATURE_UI").is_none() {
        return;
    }
    CxxQtBuilder::new_qml_module(QmlModule::new("io.github.jmarceno.gravaai").qml_files([
        QmlFile::from("qml/Main.qml"),
        QmlFile::from("qml/Theme.qml").singleton(true),
        QmlFile::from("qml/components/AppButton.qml"),
        QmlFile::from("qml/components/AppCard.qml"),
        QmlFile::from("qml/components/AppField.qml"),
        QmlFile::from("qml/components/AppSwitch.qml"),
        QmlFile::from("qml/components/StatusBadge.qml"),
        QmlFile::from("qml/components/SidebarItem.qml"),
        QmlFile::from("qml/components/TitleBar.qml"),
        QmlFile::from("qml/pages/RecorderPage.qml"),
        QmlFile::from("qml/pages/LibraryPage.qml"),
        QmlFile::from("qml/pages/JobsPage.qml"),
        QmlFile::from("qml/pages/ModelsPage.qml"),
        QmlFile::from("qml/pages/PromptsPage.qml"),
        QmlFile::from("qml/pages/GeneralPage.qml"),
        QmlFile::from("qml/pages/AboutPage.qml"),
        // Test-only scene used by the offscreen geometry/contract smoke gate.
        QmlFile::from("qml/SmokeHarness.qml"),
    ]))
    .files(["src/ui/qt/controller.rs", "src/ui/qt/runtime.rs"])
    .cpp_file("src/ui/qt/runtime.cpp")
    .qt_module("Network")
    .qt_module("Quick")
    .qt_module("QuickControls2")
    .qt_module("Svg")
    .qrc_resources(["assets/icons/hicolor/scalable/apps/gravaai.svg"])
    .build();
}
