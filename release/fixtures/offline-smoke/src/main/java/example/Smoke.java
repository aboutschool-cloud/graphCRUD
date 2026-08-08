package example;

import java.sql.Connection;
import java.sql.SQLException;

final class Smoke {
    void write(Connection connection) throws SQLException {
        connection.prepareStatement("insert into smoke_table(id) values (1)").executeUpdate();
    }
}
