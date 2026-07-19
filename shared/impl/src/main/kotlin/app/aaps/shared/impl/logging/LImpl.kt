package app.aaps.shared.impl.logging

import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LogElement
import app.aaps.core.keys.BooleanComposedKey
import app.aaps.core.keys.interfaces.Preferences
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LImpl @Inject constructor(
    private val preferences: Lazy<Preferences>
) : L {

    private var _logElements: List<LogElement>? = null
    // Map for O(1) lookups. findByName() is called on every single log statement in the app
    // (even for disabled tags), so a linear scan here is pure wasted CPU/battery 24/7.
    private var _logElementMap: Map<String, LogElement>? = null
    // Shared fallback for unknown tag names. Always disabled, never mutated, so it is safe to
    // reuse instead of allocating a throwaway LogElementImpl on every lookup miss.
    private val notFoundElement: LogElement by lazy { LogElementImpl(false, preferences.get()) }

    override fun logElements(): List<LogElement> {
        if (_logElements == null) {
            val elements = LTag.entries.map { LogElementImpl(it, preferences.get()) }
            // Built from the same instances as the list, so enable()/resetToDefault() mutations
            // stay visible through both views. Publish the map before the list so any thread that
            // observes _logElements as built also sees the map built.
            _logElementMap = elements.associateBy { it.name }
            _logElements = elements
        }
        return _logElements!!
    }

    override fun findByName(name: String): LogElement {
        var map = _logElementMap
        if (map == null) {
            logElements()
            map = _logElementMap
        }
        // Fall back to the disabled element if the map is still unbuilt (concurrent first-call
        // race). Never dereference with !!, so logging can never crash the app.
        return map?.get(name) ?: notFoundElement
    }

    override fun resetToDefaults() {
        logElements().forEach { it.resetToDefault() }
    }

    class LogElementImpl : LogElement {

        var preferences: Preferences
        override var name: String
        override var defaultValue: Boolean
        override var enabled: Boolean
        private var requiresRestart = false

        internal constructor(tag: LTag, preferences: Preferences) {
            this.preferences = preferences
            this.name = tag.tag
            this.defaultValue = tag.defaultValue
            this.requiresRestart = tag.requiresRestart
            enabled = preferences.get(BooleanComposedKey.Log, name, defaultValue = defaultValue)
        }

        internal constructor(defaultValue: Boolean, preferences: Preferences) {
            this.preferences = preferences
            name = "NONEXISTENT"
            this.defaultValue = defaultValue
            enabled = defaultValue
        }

        override fun enable(enabled: Boolean) {
            this.enabled = enabled
            preferences.put(BooleanComposedKey.Log, name, value = enabled)
        }

        override fun resetToDefault() {
            enable(defaultValue)
        }
    }
}