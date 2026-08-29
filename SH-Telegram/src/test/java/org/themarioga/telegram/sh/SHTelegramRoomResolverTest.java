package org.themarioga.telegram.sh;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.themarioga.commons.engine.models.Room;
import org.themarioga.telegram.sh.dao.intf.TelegramRoomDao;
import org.themarioga.telegram.sh.services.impl.SHTelegramRoomResolver;

/**
 * El resolutor es la frontera entre un chat de Telegram y una sala del motor, y el sitio donde el
 * motor podría acabar confundiendo dos grupos distintos.
 */
@SpringBootTest
@Transactional
class SHTelegramRoomResolverTest {

    private static final long CHAT_ID = -1001234567890L;
    private static final long OTHER_CHAT_ID = -1009876543210L;

    @Autowired
    private SHTelegramRoomResolver roomResolver;
    @Autowired
    private TelegramRoomDao telegramRoomDao;

    @Test
    void firstGameInAGroup_createsRoomAndMapping() {
        Room room = roomResolver.resolveRoom(CHAT_ID, "Grupo de pruebas");

        Assertions.assertNotNull(room);
        Assertions.assertEquals("tg:" + CHAT_ID, room.getRoomname());
        Assertions.assertEquals("Grupo de pruebas", room.getName());
        Assertions.assertEquals(true, room.getActive());

        Assertions.assertNotNull(telegramRoomDao.getByChatId(CHAT_ID));
    }

    @Test
    void secondGameInTheSameGroup_reusesTheRoom() {
        Room first = roomResolver.resolveRoom(CHAT_ID, "Grupo de pruebas");
        Room second = roomResolver.resolveRoom(CHAT_ID, "Grupo de pruebas");

        Assertions.assertEquals(first.getId(), second.getId());
    }

    /**
     * El motivo de que la identidad de la sala sea el id de chat y no el título: dos grupos pueden
     * llamarse igual, y con el título como identidad compartirían sala y por tanto partida.
     */
    @Test
    void twoGroupsWithTheSameTitle_getDifferentRooms() {
        Room one = roomResolver.resolveRoom(CHAT_ID, "Los de siempre");
        Room other = roomResolver.resolveRoom(OTHER_CHAT_ID, "Los de siempre");

        Assertions.assertNotEquals(one.getId(), other.getId());
    }

    @Test
    void renamingTheGroup_updatesTheVisibleNameButNotTheIdentity() {
        Room before = roomResolver.resolveRoom(CHAT_ID, "Nombre viejo");
        Room after = roomResolver.resolveRoom(CHAT_ID, "Nombre nuevo");

        Assertions.assertEquals(before.getId(), after.getId());
        Assertions.assertEquals("Nombre nuevo", after.getName());
        Assertions.assertEquals("tg:" + CHAT_ID, after.getRoomname());
    }

    /**
     * Telegram no siempre manda el título del chat; sin él, la sala se queda con el que ya tenía.
     */
    @Test
    void missingTitle_doesNotWipeTheName() {
        roomResolver.resolveRoom(CHAT_ID, "Con título");
        Room room = roomResolver.resolveRoom(CHAT_ID, null);

        Assertions.assertEquals("Con título", room.getName());
    }

}
