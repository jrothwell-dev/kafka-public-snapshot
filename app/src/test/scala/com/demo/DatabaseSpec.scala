package com.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

class DatabaseSpec extends AnyFlatSpec with Matchers with ForAllTestContainer {

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    dockerImageNameOverride = DockerImageName.parse("postgres:15")
  )

  "Database connection" should "work with testcontainers" in {
    val connection = DriverManager.getConnection(
      container.jdbcUrl,
      container.username,
      container.password
    )
    
    connection should not be null
    
    val statement = connection.createStatement()
    val resultSet = statement.executeQuery("SELECT 1 as test")
    
    resultSet.next() shouldBe true
    resultSet.getInt("test") shouldBe 1
    
    connection.close()
  }
  
  "Database" should "support creating tables" in {
    val connection = DriverManager.getConnection(
      container.jdbcUrl,
      container.username,
      container.password
    )
    
    val statement = connection.createStatement()
    statement.execute("""
      CREATE TABLE test_users (
        id SERIAL PRIMARY KEY,
        name VARCHAR(100)
      )
    """)
    
    statement.execute("INSERT INTO test_users (name) VALUES ('Test User')")
    
    val resultSet = statement.executeQuery("SELECT name FROM test_users")
    resultSet.next() shouldBe true
    resultSet.getString("name") shouldBe "Test User"
    
    connection.close()
  }
}