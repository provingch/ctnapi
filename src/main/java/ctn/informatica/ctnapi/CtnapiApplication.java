package ctn.informatica.ctnapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** API para todos los servicios externos que necesiten la base de datos del CTN (enfermería SHME, anotaciones, ...). */
@SpringBootApplication
public class CtnapiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CtnapiApplication.class, args);
    }
}
