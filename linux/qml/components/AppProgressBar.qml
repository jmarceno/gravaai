import QtQuick
import QtQuick.Controls
import io.github.jmarceno.gravaai

ProgressBar {
    id: root
    implicitHeight: 8
    from: 0
    to: 100
    background: Rectangle {
        radius: 4
        color: Theme.sliderTrack
        border.color: Theme.borderSubtle
        border.width: 1
    }
    contentItem: Item {
        Rectangle {
            width: root.indeterminate ? parent.width * 0.4 : parent.width * (root.visualPosition)
            height: parent.height
            radius: 4
            color: Theme.accent
            visible: root.value > 0 || root.indeterminate
        }
    }
}
