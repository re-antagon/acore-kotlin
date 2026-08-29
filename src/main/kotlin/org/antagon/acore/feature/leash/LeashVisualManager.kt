package org.antagon.acore.feature.leash

import org.bukkit.entity.Player

interface LeashVisualManager {
    val visualEnabled: Boolean

    fun createVisual(link: LeashLink)

    fun updateVisual(link: LeashLink)

    fun removeVisual(link: LeashLink)

    fun resendVisualsTo(viewer: Player, links: Collection<LeashLink>)

    fun shutdown(links: Collection<LeashLink>)
}

class NoopLeashVisualManager : LeashVisualManager {
    override val visualEnabled: Boolean = false

    override fun createVisual(link: LeashLink) = Unit

    override fun updateVisual(link: LeashLink) = Unit

    override fun removeVisual(link: LeashLink) = Unit

    override fun resendVisualsTo(viewer: Player, links: Collection<LeashLink>) = Unit

    override fun shutdown(links: Collection<LeashLink>) = Unit
}
