package org.antagon.acore.listener

import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketReceiveEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction

// Defensive incoming-packet handling for held players.
// The held player's client believes it is riding the fake vehicle:
//  - pressing sneak would normally produce a dismount ([WrapperPlayClientEntityAction]
//    START/STOP_SNEAKING) - those packets are cancelled so the server never
//    processes a sneak/dismount state change while held;
//  - position-bearing movement packets are cancelled, because the only
//    legitimate source of the held player's server-side position while held
//    is the grab tick task. This prevents rubber-bands and move-check
//    violations against Paper / anti-cheat plugins.
// Rotation-only packets are allowed so the held player can look around
class PhysicsGunPacketListener(
    private val module: PhysicsGunModule
) : PacketListenerAbstract() {

    override fun onPacketReceive(event: PacketReceiveEvent) {
        val grabManager = module.grabManager
        if (!grabManager.hasActiveSessions) return

        val uuid = event.user.uuid

        when (event.packetType) {
            PacketType.Play.Client.ENTITY_ACTION -> {
                if (!grabManager.isHeld(uuid)) return
                val wrapper = WrapperPlayClientEntityAction(event)
                when (wrapper.action) {
                    WrapperPlayClientEntityAction.Action.START_SNEAKING,
                    WrapperPlayClientEntityAction.Action.STOP_SNEAKING,
                    WrapperPlayClientEntityAction.Action.START_JUMPING_WITH_HORSE,
                    WrapperPlayClientEntityAction.Action.STOP_JUMPING_WITH_HORSE -> {
                        event.isCancelled = true
                    }
                    else -> Unit
                }
            }

            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION -> {
                if (!grabManager.isHeld(uuid)) return
                event.isCancelled = true
            }

            else -> Unit
        }
    }
}
