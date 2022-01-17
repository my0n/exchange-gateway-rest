package exchange.core2.rest;

import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ComponentScan("exchange.core2.rest")
public class TestConfiguraton {

    @Bean
    public DataSource getDataSource() {
        DataSourceBuilder dataSourceBuilder = DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url("jdbc:h2:mem:db;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("sa");
        return dataSourceBuilder.build();
    }

}
