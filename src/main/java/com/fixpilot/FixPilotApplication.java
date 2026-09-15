package com.fixpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entrypoint only - wires nothing up itself. Everything interesting is in
 * config/ (typed properties + the RestClient and executor beans),
 * service/ (the three tools plus the orchestrator that sequences them),
 * and controller/ (the webhook trigger and the read-only status endpoints).
 */
@SpringBootApplication
public class FixPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(FixPilotApplication.class, args);
    }
}
