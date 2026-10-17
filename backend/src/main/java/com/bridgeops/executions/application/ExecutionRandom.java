package com.bridgeops.executions.application;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class ExecutionRandom { public double sample(){return ThreadLocalRandom.current().nextDouble();} }
