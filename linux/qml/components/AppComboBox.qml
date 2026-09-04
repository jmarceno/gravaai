import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

ComboBox {
    id: root
    implicitHeight: 38
    font.pixelSize: 13
    background: Rectangle {
        radius: Theme.radiusSm
        color: Theme.inputBg
        border.color: root.activeFocus || root.popup.visible ? Theme.accent : Theme.borderSubtle
        border.width: root.activeFocus || root.popup.visible ? 2 : 1
    }
    contentItem: Label {
        text: root.displayText
        color: Theme.textPrimary
        font.pixelSize: 13
        verticalAlignment: Text.AlignVCenter
        leftPadding: 12
        rightPadding: 30
        elide: Text.ElideRight
    }
    indicator: Text {
        text: "▾"
        color: Theme.textMuted
        font.pixelSize: 13
        anchors.right: parent.right
        anchors.rightMargin: 12
        anchors.verticalCenter: parent.verticalCenter
    }
    popup: Popup {
        y: root.height + 4
        width: root.width
        padding: 6
        background: Rectangle {
            radius: Theme.radiusSm
            color: Theme.cardBgRaised
            border.color: Theme.borderSubtle
            border.width: 1
        }
        contentItem: ListView {
            clip: true
            implicitHeight: Math.min(240, contentHeight)
            model: root.delegateModel
            currentIndex: root.highlightedIndex
            ScrollBar.vertical: ScrollBar {}
        }
    }
    delegate: ItemDelegate {
        required property var modelData
        required property int index
        width: ListView.view ? ListView.view.width : root.width - 12
        highlighted: root.highlightedIndex === index
        background: Rectangle {
            radius: 6
            color: highlighted ? Theme.accent : "transparent"
        }
        contentItem: Label {
            text: String(modelData)
            color: highlighted ? "#ffffff" : Theme.textPrimary
            font.pixelSize: 13
            elide: Text.ElideRight
            verticalAlignment: Text.AlignVCenter
        }
        onClicked: {
            root.currentIndex = index
            root.popup.close()
        }
    }
}
