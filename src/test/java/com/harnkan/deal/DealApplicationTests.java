package com.harnkan.deal;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Needs a real MySQL (and, once wired, a reachable Payment Service) to boot the full
// context. Re-enable once docker-compose (or a local MySQL) is part of the dev setup.
// (Disabled at the class level — a method-level @Disabled still lets Spring's test
// extension try to build the ApplicationContext during instance post-processing.)
@Disabled("requires a running MySQL instance — see application.yaml datasource config")
@SpringBootTest
class DealApplicationTests {

	@Test
	void contextLoads() {
	}

}
