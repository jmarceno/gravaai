pragma Singleton
import QtQuick

QtObject {
    readonly property color windowBg: "#181a1f"
    readonly property color cardBg: "#21252b"
    readonly property color cardBgRaised: "#272c33"
    readonly property color accent: "#3bb2a4"
    readonly property color accentMuted: "#3bb2a433"
    readonly property color accentSoft: "#3bb2a41a"
    readonly property color textPrimary: "#ffffff"
    readonly property color textSecondary: "#c5c9ce"
    readonly property color textMuted: "#8b929a"
    readonly property color textDim: "#6b7280"
    readonly property color statusGreen: "#3ecf8e"
    readonly property color danger: "#e35d6a"
    readonly property color borderSubtle: "#2c313a"
    readonly property color inputBg: "#16191e"
    readonly property color sliderTrack: "#2a2f38"
    readonly property color accentText: textPrimary
    readonly property color hover: cardBgRaised
    readonly property color scrim: cardBg
    // Qt 6 does not expose a portable reduced-motion flag through
    // QStyleHints. Honour an explicit accessibility request without probing
    // a non-existent property (which would produce a QML startup warning).
    readonly property bool animationsEnabled: !(Qt.application
        && Qt.application.arguments
        && Qt.application.arguments.indexOf("--reduce-motion") >= 0)
    readonly property int animationDuration: animationsEnabled ? 180 : 0
    readonly property int radius: 10
    readonly property int radiusSm: 8
    readonly property int sidebarWidth: 236
}
