package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class Main {
    /*
     * Server commands for this project:
     * ssh -i D:\pzl\pzl.pem root@47.91.31.90
     * scp upload:   scp -i D:\pzl\pzl.pem target/order-1.0.jar root@47.91.31.90:~
     * scp download: scp -i D:\pzl\pzl.pem root@47.91.31.90:~/order-1.0.jar target/order-1.0.jar
     */
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }
}
