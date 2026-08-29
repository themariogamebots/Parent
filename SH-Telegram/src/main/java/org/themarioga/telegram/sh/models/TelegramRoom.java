package org.themarioga.telegram.sh.models;

import jakarta.persistence.*;
import org.themarioga.commons.engine.models.Room;

import java.io.Serializable;
import java.util.Objects;

/**
 * Equivalencia entre un grupo de Telegram y una sala del motor.
 * <p>
 * Es <b>permanente</b>: el grupo sobrevive a las partidas que se juegan en él, y esta fila se crea
 * la primera vez que se juega y se reutiliza siempre. Lo que muere con cada partida son
 * {@link TelegramGame} y {@link TelegramPlayer}.
 */
@Entity
@Table(name = "telegram_room")
public class TelegramRoom implements Serializable {

    /**
     * Id del chat de grupo de Telegram. Lo asigna Telegram, no se genera.
     */
    @Id
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, unique = true)
    private Room room;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Room getRoom() {
        return room;
    }

    public void setRoom(Room room) {
        this.room = room;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        TelegramRoom that = (TelegramRoom) o;
        return Objects.equals(getId(), that.getId());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getId());
    }

    @Override
    public String toString() {
        return "TelegramRoom{id=" + id + ", room=" + room + '}';
    }

}
