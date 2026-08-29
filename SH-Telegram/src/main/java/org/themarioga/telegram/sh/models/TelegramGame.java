package org.themarioga.telegram.sh.models;

import jakarta.persistence.*;
import org.themarioga.engine.sh.models.Game;

import java.io.Serializable;
import java.util.Objects;

/**
 * Mensajes de Telegram asociados a una partida en curso.
 * <p>
 * Es <b>efímera</b>: los identificadores de mensaje solo tienen sentido mientras dura la partida y
 * la fila se borra con ella. La equivalencia duradera entre el grupo y la sala está en
 * {@link TelegramRoom}.
 * <p>
 * El tablero y la fase en curso son dos mensajes distintos a propósito: el tablero (leyes
 * promulgadas, elecciones fallidas, veto) es información acumulativa que interesa tener a la vista
 * toda la partida, y la fase (nominación, votación, "el presidente está legislando") es volátil y
 * de una ronda concreta. En un único mensaje, cada votación empujaría el tablero fuera de la
 * pantalla.
 */
@Entity
@Table(name = "telegram_game")
public class TelegramGame implements Serializable {

    @Id
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    /**
     * Mensaje del grupo con el menú de la partida y el botón de unirse.
     */
    @Column(name = "first_message_id", nullable = false)
    private Integer firstMessageId;

    /**
     * Mensaje privado desde el que el creador configura la partida.
     */
    @Column(name = "creator_message_id", nullable = false)
    private Integer creatorMessageId;

    /**
     * Mensaje del grupo con el tablero, que se edita según avanza la partida.
     */
    @Column(name = "board_message_id")
    private Integer boardMessageId;

    /**
     * Mensaje del grupo con la fase en curso de la ronda (nominación, votación, legislación).
     */
    @Column(name = "current_round_message_id")
    private Integer currentRoundMessageId;

    public Game getGame() {
        return game;
    }

    public void setGame(Game game) {
        this.game = game;
    }

    public Integer getFirstMessageId() {
        return firstMessageId;
    }

    public void setFirstMessageId(Integer firstMessageId) {
        this.firstMessageId = firstMessageId;
    }

    public Integer getCreatorMessageId() {
        return creatorMessageId;
    }

    public void setCreatorMessageId(Integer creatorMessageId) {
        this.creatorMessageId = creatorMessageId;
    }

    public Integer getBoardMessageId() {
        return boardMessageId;
    }

    public void setBoardMessageId(Integer boardMessageId) {
        this.boardMessageId = boardMessageId;
    }

    public Integer getCurrentRoundMessageId() {
        return currentRoundMessageId;
    }

    public void setCurrentRoundMessageId(Integer currentRoundMessageId) {
        this.currentRoundMessageId = currentRoundMessageId;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        TelegramGame that = (TelegramGame) o;
        return Objects.equals(getGame(), that.getGame());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(getGame());
    }

    @Override
    public String toString() {
        return "TelegramGame{game=" + game + ", firstMessageId=" + firstMessageId + ", creatorMessageId=" + creatorMessageId + ", boardMessageId=" + boardMessageId + ", currentRoundMessageId=" + currentRoundMessageId + '}';
    }

}
