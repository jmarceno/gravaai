import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

Button {
    id: root
    property string variant: "primary"
    implicitHeight: 38
    leftPadding: 16
    rightPadding: 16
    topPadding: 8
    bottomPadding: 8
    font.pixelSize: 13
    font.bold: true
    background: Rectangle {
        radius: Theme.radiusSm
        color: {
            if (!root.enabled) return Theme.cardBgRaised
            if (root.variant === "danger") return root.down ? "#c94f58" : Theme.danger
            if (root.variant === "secondary") return root.down ? Theme.cardBgRaised : (root.hovered ? Theme.cardBgRaised : Theme.inputBg)
            if (root.variant === "teal") return root.down ? "#238a7f" : (root.hovered ? "#35c2b2" : Theme.accent)
            if (root.variant === "warning") return root.down ? "#c9962a" : (root.hovered ? "#f0c44c" : Theme.warning)
            return root.down ? Theme.hover : (root.hovered ? "#3abfae" : Theme.accent)
        }
        border.color: {
            if (!root.enabled) return Theme.borderSubtle
            if (root.variant === "danger") return Theme.danger
            if (root.variant === "secondary") return Theme.borderSubtle
            if (root.variant === "warning") return Theme.warning
            return Theme.accent
        }
        border.width: 1
    }
    contentItem: Text {
        text: root.text
        color: {
            if (!root.enabled) return Theme.textDim
            if (root.variant === "secondary") return Theme.textSecondary
            if (root.variant === "warning") return "#1a1503"
            return "#ffffff"
        }
        font: root.font
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }
}
