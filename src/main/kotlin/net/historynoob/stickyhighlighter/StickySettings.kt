package net.historynoob.stickyhighlighter

import com.intellij.openapi.components.*

@State(
    name = "StickyHighlighterSettings",
    storages = [Storage("stickyhighlighter.xml")]
)
@Service(Service.Level.APP)
class StickySettings : PersistentStateComponent<StickySettings.State> {
    data class State(
        var fillColorArgb: Int = 0x66FFf59D.toInt(),
        var stripeColorArgb: Int = 0xFFCC9A06.toInt(),
        var opacity: Int = 40
    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state}

    companion object {
        fun instance(): StickySettings = service()
    }
}