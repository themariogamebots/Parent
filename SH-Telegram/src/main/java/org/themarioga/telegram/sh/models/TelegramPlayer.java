package org.themarioga.telegram.sh.models;

import jakarta.persistence.*;
import org.themarioga.engine.sh.models.Player;

import java.io.Serializable;
import java.util.Objects;

/**
 * Mensajes privados de un jugador durante la partida.
 * <p>
 * Efímera como {@link TelegramGame}: se borra al terminar la partida.
 * <p>
 * Son dos mensajes y no uno: el del rol se envía una vez al empezar y no se toca más, y el de la
 * acción pendiente (elegir canciller, descartar, promulgar, elegir objetivo de un poder) se edita
 * cada vez que a ese jugador le toca algo.
 */
@Entity
@Table(name = "telegram_player")
public class TelegramPlayer implements Serializable {

    @Id
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Column(name = "role_message_id", nullable = false)
    private Integer roleMessageId;

    @Column(name = "action_message_id")
    private Integer actionMessageId;

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public Integer getRoleMessageId() {
        return roleMessageId;
    }

    public void setRoleMessageId(Integer roleMessageId) {
        this.roleMessageId = roleMessageId;
    }

    public Integer getActionMessageId() {
        return actionMessageId;
    }

    public void setActionMessageId(Integer actionMessageId) {
        this.actionMessageId = actionMessageId;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        TelegramPlayer that = (TelegramPlayer) o;
        return Objects.equals(getPlayer(), that.getPlayer());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getPlayer());
    }

    @Override
    public String toString() {
        return "TelegramPlayer{player=" + player + ", roleMessageId=" + roleMessageId + ", actionMessageId=" + actionMessageId + '}';
    }

}
