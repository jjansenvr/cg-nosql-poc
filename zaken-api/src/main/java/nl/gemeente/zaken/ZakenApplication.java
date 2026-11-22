package nl.gemeente.zaken;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"nl.gemeente.zaken", "nl.commonground.engine"})
public class ZakenApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZakenApplication.class, args);
    }
}
