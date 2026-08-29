package org.themarioga.telegram.sh;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;

/**
 * Aplicación desplegable del bot de Secret Hitler.
 * <p>
 * El escaneo abarca {@code org.themarioga} entero porque los beans y las entidades vienen de tres
 * módulos distintos: {@code commons-engine}, {@code sh-engine} y {@code commons-telegram}.
 * <p>
 * Despliegue y base de datos propios, separados de los de CAH-Telegram: las entidades {@code Game},
 * {@code Player} y {@code Round} de los dos motores tienen el mismo nombre simple y, con la herencia
 * {@code TABLE_PER_CLASS} que usan, mapearían a las mismas tablas. Ver la decisión D1 de
 * docs/specs/SH-Telegram-PLAN.md.
 */
@SpringBootApplication(scanBasePackages = "org.themarioga")
@EntityScan(basePackages = {"org.themarioga"})
public class SHTelegramApplication {

    public static void main(String[] args) {
        SpringApplication.run(SHTelegramApplication.class, args);
    }

}
