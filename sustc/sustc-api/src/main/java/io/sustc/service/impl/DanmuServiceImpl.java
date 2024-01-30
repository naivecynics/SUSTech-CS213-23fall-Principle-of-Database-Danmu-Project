package io.sustc.service.impl;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.sustc.dto.AuthInfo;
import io.sustc.service.DanmuService;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class DanmuServiceImpl implements DanmuService{

    @Autowired
    private DataSource dataSource;

    @Override
    public List<Long> displayDanmu(String bv, float timeStart, float timeEnd, boolean filter) { // Accepted

        if (bv == null || bv.isEmpty()) return null;
        if (timeStart > timeEnd || timeStart < 0 || timeEnd < 0) return null;
        if (!isVideoExist(bv)) return null;

        // 判断是否大于视频长度
        String  sqlCheckDuration = "SELECT duration FROM videos WHERE bv = ?";
        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sqlCheckDuration)) {
            stmt.setString(1, bv);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (timeStart > rs.getInt("duration") || timeEnd > rs.getInt("duration")) {
                    return null;
                }
            } else {
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Check if video exists failed", e);
        }

        String sql = "SELECT id FROM danmus WHERE bv = ? AND time >= ? AND time <= ? ORDER BY time";

        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, bv);
            stmt.setFloat(2, timeStart);
            stmt.setFloat(3, timeEnd);

            List<Long> danmuList = new ArrayList<>();

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                if (filter) {
                    danmuList.add(rs.getLong("id"));
                } else {
                    danmuList.add(rs.getLong("id"));
                }
            }
            return danmuList;
        } catch (SQLException e) {
            throw new RuntimeException("Display danmu failed", e);
        }
    }

    @Override
    public boolean likeDanmu(AuthInfo auth, long id) {

        if (isAuthValid(auth) == 0) return false;
        if (id <= 0) return false;
        long mid = getMidByAuth(auth);

        try (Connection conn = dataSource.getConnection()) {

            // 处理点赞
            String sql = "SELECT bv, liked_by FROM danmus WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, id);
                ResultSet rs = stmt.executeQuery();

                boolean tag = false;

                if (rs.next()) {
                    // 检查是否已观看
                    String sqlCheckWatched = "SELECT id FROM users_watch WHERE bv = ? AND mid = ?;";
                    try (PreparedStatement stmt2 = conn.prepareStatement(sqlCheckWatched)) {
                        stmt2.setString(1, rs.getString("bv"));
                        stmt2.setLong(2, mid);
                        ResultSet rs2 = stmt2.executeQuery();
                        if (!rs2.next()) {
                            return false;
                        }
                    }

                    // 检查是否已点赞
                    ArrayList<String> liked_by_list = new ArrayList<String>();
                    ArrayList<String> finalLiked_by_list = new ArrayList<String>();
                    if (rs.getString("liked_by") != null && !rs.getString("liked_by").isEmpty() && !rs.getString("liked_by").equals("null")) {
                        if (rs.getString("liked_by").contains(String.valueOf(mid))) {
                            tag = true;
                            liked_by_list = new ArrayList<String>(Arrays.asList(rs.getString("liked_by").split(",")));
                            liked_by_list.remove (String.valueOf(mid));
                        }
                        else {
                            liked_by_list = new ArrayList<String>(Arrays.asList(rs.getString("liked_by").split(",")));
                            liked_by_list.add (String.valueOf(mid));
                        }
                        for (String liked : liked_by_list) {
                            liked = fixString(liked);
                            if (!liked.isEmpty() && !liked.equals("null") && !liked.equals("{}")) {
                                finalLiked_by_list.add(liked);
                            }
                        }

                    }
                    // 点赞
                    String sqlLike = "UPDATE danmus SET liked_by = ? WHERE id = ?";
                    try (PreparedStatement stmt2 = conn.prepareStatement(sqlLike)) {
                        stmt2.setArray (1, conn.createArrayOf("long", finalLiked_by_list.toArray()));
                        stmt2.setLong(2, id);
                        stmt2.executeUpdate();
                    }
                    if (tag) return false;
                    else return true;
                } else {
                    return false;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database operation failed", e);
        }
    }

    private String fixString(String str) {
        // 找到并删去删去id中的 { } " "号
        if (str.startsWith("{")) {
            str = str.substring(1);
        }
        if (str.endsWith("}")) {
            str = str.substring(0, str.length() - 1);
        }
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0, str.length() - 1);
        }
        return str;
    }

    @Override
    public long sendDanmu(AuthInfo auth, String bv, String content, float time) {
        int id = isAuthValid(auth);
        if (id == 0) return -1;
        if (!isVideoExist(bv)) return -1;

        try (Connection conn = dataSource.getConnection()) {
            // check if watched first
            String sql = "SELECT id FROM users_watch WHERE bv = ? AND mid = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, bv);
                stmt.setLong(2, getMidByAuth(auth));
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    return -2;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Check if video exists failed", e);
            }

            String sqlCheckDuration = "SELECT duration FROM videos WHERE bv = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlCheckDuration)) {
                stmt.setString(1, bv);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    if (time > rs.getInt("duration")) {
                        return -1;
                    }
                } else {
                    return -1;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Check if video exists failed", e);
            }

            // insert danmu
            String sqlAddDanmu = "INSERT INTO danmus (bv, mid, content, time, post_time) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sqlAddDanmu, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setString(1, bv);
                stmt.setLong(2, auth.getMid());
                stmt.setString(3, content);
                stmt.setFloat(4, time);
                stmt.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now()));

                stmt.executeUpdate();
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getLong(1); // 获取自动生成的id
                    } else {
                        throw new SQLException("Creating danmu failed, no ID obtained.");
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Add danmu failed", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database connection failed", e);
        }
    }


    int isAuthValid (AuthInfo auth) {
        // 判断了id与登录的合法性
        // return 0: 不合法 1: 普通用户 2: 管理员

        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT password, identity FROM users WHERE mid = ?";
            boolean byPassword = true;
            String varText = null;
            if (auth.getQq() != null) {
                sql = "SELECT password, identity FROM users WHERE qq = ?";
                varText = auth.getQq();
                byPassword = false;
            }
            if (auth.getWechat() != null) {
                sql = "SELECT password, identity FROM users WHERE wechat = ?";
                varText = auth.getWechat();
                byPassword = false;
            }
            PreparedStatement stmt = conn.prepareStatement(sql);
            if (byPassword) {
                stmt.setLong(1, auth.getMid());
            } else {
                stmt.setString(1, varText);
            }

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (byPassword && (auth.getPassword() == null || !auth.getPassword().equals(rs.getString("password")))) {
                    return 0;
                }
                if (rs.getString("identity").equals("superuser")) {
                    return 2;
                } else {
                    return 1;
                }
            } else {
                return 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private long getMidByAuth(AuthInfo auth) {
        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT mid FROM users WHERE mid = ?";
            boolean byPassword = true;
            String varText = null;
            if (auth.getQq() != null) {
                sql = "SELECT mid FROM users WHERE qq = ?";
                varText = auth.getQq();
                byPassword = false;
            }
            if (auth.getWechat() != null) {
                sql = "SELECT mid FROM users WHERE wechat = ?";
                varText = auth.getWechat();
                byPassword = false;
            }
            PreparedStatement stmt = conn.prepareStatement(sql);
            if (byPassword) {
                stmt.setLong(1, auth.getMid());
            } else {
                stmt.setString(1, varText);
            }
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getLong("mid");
            } else {
                return 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    private boolean isVideoExist(String bv) {

        String sql = "SELECT title, owner_name, description, deleted, duration FROM videos WHERE bv = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, bv);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                if (rs.getString("title") == null || rs.getString("owner_name") == null
                        || rs.getString("description") == null || rs.getInt("duration") == 0) {
                    return false;
                }
                if (rs.getBoolean("deleted")) return false;
                return true;
            }
            return false;
        } catch (SQLException e) {
            throw new RuntimeException("Check if video exists failed", e);
        }
    }
}
