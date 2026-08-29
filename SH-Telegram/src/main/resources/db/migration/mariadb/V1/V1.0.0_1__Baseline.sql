-- v1.0.0_1 - Baseline del esquema (mariadb)
--
-- Generado desde las entidades con SH-Telegram/src/test/java/.../tools/SchemaGenerator.java,
-- aplicando las naming strategies de Spring Boot 4.1 (SpringImplicitNamingStrategy +
-- PhysicalNamingStrategySnakeCaseImpl). No editar a mano: regenerar si cambia el modelo.
--
-- Base de datos propia, separada de la de CAH-Telegram: las entidades Game, Player y Round de los
-- dos motores tienen el mismo nombre simple y, con la herencia TABLE_PER_CLASS que usan, mapearían
-- a las mismas tablas. Ver la decisión D1 de docs/specs/SH-Telegram-PLAN.md.

    create table game (
        id uuid not null,
        creation_date datetime(6) not null,
        status tinyint not null check ((status between 0 and 3)),
        creator_id uuid not null,
        room_id uuid not null,
        failed_votation_counter integer not null,
        last_regular_president_join_order integer,
        max_number_of_players integer not null,
        number_of_fascist_laws_enacted integer not null,
        number_of_liberal_laws_enacted integer not null,
        result varchar(255) check ((result in ('LIBERAL_LAWS','FASCIST_LAWS','HITLER_EXECUTED','HITLER_ELECTED_CHANCELLOR'))),
        veto_is_active bit not null,
        previous_round_chancellor_id uuid,
        previous_round_president_id uuid,
        special_election_president_id uuid,
        primary key (id)
    ) engine=InnoDB;

    create table game_deletion_votes (
        game_id uuid not null,
        user_id uuid not null
    ) engine=InnoDB;

    create table game_law_discard_deck (
        game_id uuid not null,
        law_id bigint not null
    ) engine=InnoDB;

    create table game_law_pick_deck (
        game_id uuid not null,
        law_id bigint not null
    ) engine=InnoDB;

    create table lang (
        id varchar(255) not null,
        name varchar(256) not null,
        primary key (id)
    ) engine=InnoDB;

    create table law (
        id bigint not null auto_increment,
        type varchar(255) not null check ((type in ('FASCIST','LIBERAL'))),
        primary key (id)
    ) engine=InnoDB;

    create table player (
        id uuid not null,
        creation_date datetime(6) not null,
        join_order integer not null,
        game_id uuid not null,
        user_id uuid not null,
        alive bit not null,
        investigated bit not null,
        party varchar(255) check ((party in ('LIBERAL','FASCIST'))),
        role varchar(255) check ((role in ('LIBERAL','FASCIST','HITLER'))),
        primary key (id)
    ) engine=InnoDB;

    create table room (
        id uuid not null,
        creation_date datetime(6) not null,
        active bit not null,
        name varchar(256) not null,
        roomname varchar(128) not null,
        primary key (id)
    ) engine=InnoDB;

    create table round (
        id uuid not null,
        creation_date datetime(6) not null,
        round_number integer not null,
        status varchar(255) not null check ((status in ('SELECTING_CHANCELLOR','VOTING_CHANCELLOR','PRESIDENT_DISCARDING_LAW','CHANCELLOR_SELECTING_LAW','DOING_ADDITIONAL_ACTION','ARGUING','ENDING','HITLER_ELECTED_CHANCELLOR','CHANCELLOR_REJECTED','VETO_REQUESTED'))),
        chancellor_id uuid,
        game_id uuid not null,
        president_id uuid,
        primary key (id)
    ) engine=InnoDB;

    create table round_available_laws (
        round_id uuid not null,
        law_id bigint not null
    ) engine=InnoDB;

    create table round_votes (
        round_id uuid not null,
        vote varchar(255) not null check ((vote in ('YES','NO'))),
        player_id uuid not null,
        primary key (round_id, player_id)
    ) engine=InnoDB;

    create table tag (
        tag varchar(255) not null,
        text varchar(4000) not null,
        lang_id varchar(255) not null,
        primary key (lang_id, tag)
    ) engine=InnoDB;

    create table telegram_game (
        board_message_id integer,
        creator_message_id integer not null,
        current_round_message_id integer,
        first_message_id integer not null,
        game_id uuid not null,
        primary key (game_id)
    ) engine=InnoDB;

    create table telegram_player (
        action_message_id integer,
        role_message_id integer not null,
        player_id uuid not null,
        primary key (player_id)
    ) engine=InnoDB;

    create table telegram_room (
        id bigint not null,
        room_id uuid not null,
        primary key (id)
    ) engine=InnoDB;

    create table telegram_user (
        id bigint not null,
        language_code varchar(8),
        last_seen datetime(6),
        user_id uuid not null,
        primary key (id)
    ) engine=InnoDB;

    create table users (
        id uuid not null,
        creation_date datetime(6) not null,
        active bit not null,
        name varchar(256) not null,
        username varchar(64) not null,
        lang_id varchar(255) not null,
        primary key (id)
    ) engine=InnoDB;

    alter table if exists game 
       add constraint UKgywp3n4k6l3vjewd3ad4sa7xu unique (creator_id);

    alter table if exists game 
       add constraint UKp5xpypoxlw4ponq4lb0rj4q0g unique (room_id);

    alter table if exists game_deletion_votes 
       add constraint UK5nw2597nf81u2ermis0oitytr unique (user_id);

    alter table if exists game_law_discard_deck 
       add constraint UKajs877vuouvxcdt6o1ighn68 unique (law_id);

    alter table if exists game_law_pick_deck 
       add constraint UKof9x8jn9p6gpb70aydktwx2a7 unique (law_id);

    alter table if exists lang 
       add constraint UKrv5wt7gk16yu6flfxm95vh76y unique (name);

    alter table if exists player 
       add constraint UK2cpdse0bal7ll4q0owr20oc71 unique (user_id);

    create index IDX4l8mm4fqoos6fcbx76rvqxer 
       on room (name);

    alter table if exists room 
       add constraint UKg89ln7s4jt9idnd5xnw1mf1pq unique (roomname);

    alter table if exists round 
       add constraint UKbdil5s8awg1diwle2l1eb3tbx unique (game_id);

    alter table if exists round_available_laws 
       add constraint UKbk1dh3jb2xurp4hqex12mn5rs unique (law_id);

    alter table if exists telegram_room 
       add constraint UKltqe0101mn9sfipnumyu0yuk7 unique (room_id);

    alter table if exists telegram_user 
       add constraint UKagsvbr4mhfj49c96foedgq8wk unique (user_id);

    create index IDX3g1j96g94xpk3lpxl2qbl985x 
       on users (name);

    alter table if exists users 
       add constraint UKr43af9ap4edm43mmtq01oddj6 unique (username);

    alter table if exists game 
       add constraint FKgaa020froocdx7nd9ei1tbluq 
       foreign key (previous_round_chancellor_id) 
       references player (id);

    alter table if exists game 
       add constraint FK3m522mpi951vsqtjkqq99nbjs 
       foreign key (previous_round_president_id) 
       references player (id);

    alter table if exists game 
       add constraint FKaqt540mda5ntxs7vq4i97hfcj 
       foreign key (special_election_president_id) 
       references player (id);

    alter table if exists game 
       add constraint FKlg27yqk2n245fmd22auiffyp1 
       foreign key (creator_id) 
       references users (id);

    alter table if exists game 
       add constraint FKn2dw11xgfbg2agx7v25teb8b 
       foreign key (room_id) 
       references room (id);

    alter table if exists game_deletion_votes 
       add constraint FKbiuyrsgekoq3ngsposbvt3kax 
       foreign key (user_id) 
       references users (id);

    alter table if exists game_law_discard_deck 
       add constraint FKnd4r5hq9m1v9jk77lfqmlo1p6 
       foreign key (law_id) 
       references law (id);

    alter table if exists game_law_discard_deck 
       add constraint FK4qhxc8d4q9y0e00ro49fputp0 
       foreign key (game_id) 
       references game (id);

    alter table if exists game_law_pick_deck 
       add constraint FKc65u4aoxj0uhlfijxmo7u3a3g 
       foreign key (law_id) 
       references law (id);

    alter table if exists game_law_pick_deck 
       add constraint FKeghhgomtheio6jcc5i1vdnjmb 
       foreign key (game_id) 
       references game (id);

    alter table if exists player 
       add constraint FKoycxb69gpaapuv23fn52y0g50 
       foreign key (user_id) 
       references users (id);

    alter table if exists round 
       add constraint FK5yc5lox8s6oghlh62nc55eoxr 
       foreign key (chancellor_id) 
       references player (id);

    alter table if exists round 
       add constraint FKppxonwn9e98lccy46m2eve67m 
       foreign key (game_id) 
       references game (id);

    alter table if exists round 
       add constraint FKbrt4f1cewnfqflvqf96h5g81j 
       foreign key (president_id) 
       references player (id);

    alter table if exists round_available_laws 
       add constraint FKql4fgg2jr9u4smdax3nkjl5e1 
       foreign key (law_id) 
       references law (id);

    alter table if exists round_available_laws 
       add constraint FK82ps36li4w1no5vl3crd0l8im 
       foreign key (round_id) 
       references round (id);

    alter table if exists round_votes 
       add constraint FKj5skl2so858mvt1rgejspo52f 
       foreign key (player_id) 
       references player (id);

    alter table if exists round_votes 
       add constraint FKtc8na4gfdpm8jsyyv1bkt80wx 
       foreign key (round_id) 
       references round (id);

    alter table if exists tag 
       add constraint FKfths0gjayk5sgph0wmt26xmqr 
       foreign key (lang_id) 
       references lang (id);

    alter table if exists telegram_game 
       add constraint FK6pnautggosoc7dd8iki7m0s9x 
       foreign key (game_id) 
       references game (id);

    alter table if exists telegram_player 
       add constraint FKommegdf9a3u5wc3md30b2hgix 
       foreign key (player_id) 
       references player (id);

    alter table if exists telegram_room 
       add constraint FKp9i88sufkoffr3dskuslsljl2 
       foreign key (room_id) 
       references room (id);

    alter table if exists telegram_user 
       add constraint FK4fpwstgmikyoruwr0t96q434r 
       foreign key (user_id) 
       references users (id);

    alter table if exists users 
       add constraint FK5tj0aisj3e9c62bc75fy2he87 
       foreign key (lang_id) 
       references lang (id);
