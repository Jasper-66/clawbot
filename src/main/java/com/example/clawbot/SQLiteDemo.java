package com.example.clawbot;

import java.sql.*;

public class SQLiteDemo {
    // SQLite连接地址：jdbc:sqlite:数据库文件名，不存在则自动创建test.db
    private static final String DB_URL = "jdbc:sqlite:test.db";

    public static void main(String[] args) {
        // 1. 连接数据库 + 执行所有操作
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            System.out.println("✅ SQLite数据库连接成功！");

            // 2. 创建数据表 students
            createTable(conn);

            // 3. 插入测试数据
            insertData(conn);

            // 4. 查询全部数据
            queryAllStudent(conn);

            // 5. 条件查询示例
            queryByAge(conn);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建学生表 students
     */
    public static void createTable(Connection conn) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS students (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    age INTEGER,
                    major TEXT
                )
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            System.out.println("✅ 数据表创建完成（表存在则跳过）");
        }
    }

    /**
     * 插入数据（推荐PreparedStatement防SQL注入）
     */
    public static void insertData(Connection conn) throws SQLException {
        String sql = "INSERT INTO students(name, age, major) VALUES (?, ?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            // 插入第一条
            pstmt.setString(1, "张三");
            pstmt.setInt(2, 20);
            pstmt.setString(3, "软件工程");
            pstmt.executeUpdate();

            // 第二条
            pstmt.setString(1, "李四");
            pstmt.setInt(2, 19);
            pstmt.setString(3, "计算机科学");
            pstmt.executeUpdate();

            // 第三条
            pstmt.setString(1, "王五");
            pstmt.setInt(2, 21);
            pstmt.setString(3, "人工智能");
            pstmt.executeUpdate();
        }
        System.out.println("✅ 数据插入完成");
    }

    /**
     * 查询全部学生
     */
    public static void queryAllStudent(Connection conn) throws SQLException {
        String sql = "SELECT * FROM students";
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            System.out.println("\n📋 全部学生数据：");
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                int age = rs.getInt("age");
                String major = rs.getString("major");
                System.out.printf("id:%d 姓名:%s 年龄:%d 专业:%s%n", id, name, age, major);
            }
        }
    }

    /**
     * 条件查询：年龄≥20的学生
     */
    public static void queryByAge(Connection conn) throws SQLException {
        String sql = "SELECT name, age FROM students WHERE age >= ?";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, 20);
            try (ResultSet rs = pstmt.executeQuery()) {
                System.out.println("\n📋 年龄≥20岁学生：");
                while (rs.next()) {
                    String name = rs.getString("name");
                    int age = rs.getInt("age");
                    System.out.printf("姓名:%s 年龄:%d%n", name, age);
                }
            }
        }
    }
}
