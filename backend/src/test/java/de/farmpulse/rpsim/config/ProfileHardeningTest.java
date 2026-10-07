package de.farmpulse.rpsim.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Properties;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

/** Review 10/2026 Phase 0.1 / 0.5: what the dev and prod profile files must (not) contain, and what that does. */
class ProfileHardeningTest {

    static Properties profile(String name) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-" + name + ".yml"));
        return yaml.getObject();
    }

    @Test
    void noH2TcpServerAndAPasswordFileInDevAndProd() {
        for (String name : new String[] {"dev", "prod"}) {
            Properties p = profile(name);
            assertThat(p.getProperty("spring.datasource.url")).as(name).startsWith("jdbc:h2:file:")
                    .doesNotContainIgnoringCase("AUTO_SERVER");
            assertThat(p.getProperty("rpsim.db.password-file")).as(name).endsWith("db.properties");
        }
        assertThat(profile("prod").getProperty("rpsim.db.password-file")).isEqualTo("${user.home}/.rpsim/db.properties");
    }

    @Test
    void prodServesNoOpenApiDocument() {
        Properties p = profile("prod");
        assertThat(p.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(p.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
    }

    /** The two switches of the prod profile really remove the endpoints. */
    @Nested
    @SpringBootTest(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
    @AutoConfigureMockMvc
    class WithTheProdSwitches {

        @Autowired
        MockMvc mvc;

        @Test
        void apiDocsAndSwaggerUiAreGone() throws Exception {
            mvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
            mvc.perform(get("/api/settings/ai")).andExpect(status().isOk());
        }
    }
}
