package cl.duoc.pedidos360.rabbitadmin.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * El RabbitAdmin que usa el servicio de administracion.
 *
 * Se declara a mano, y no se usa el que autoconfigura Spring Boot, por un
 * motivo concreto: este se crea con setAutoStartup(false), de modo que no
 * intenta declarar nada al arrancar. Un RabbitAdmin normal declara en el broker
 * todos los Queue, Exchange y Binding que encuentre en el contexto; aqui no hay
 * ninguno, pero dejarlo apagado deja explicito que este microservicio solo toca
 * la topologia cuando alguien se lo pide por la API.
 */
@Configuration
public class RabbitAdminConfig {

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(false);
        return admin;
    }
}
