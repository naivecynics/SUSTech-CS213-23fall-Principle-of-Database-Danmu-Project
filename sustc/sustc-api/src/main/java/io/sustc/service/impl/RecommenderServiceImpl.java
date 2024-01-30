package io.sustc.service.impl;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.sustc.dto.AuthInfo;
import io.sustc.service.RecommenderService;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class RecommenderServiceImpl implements RecommenderService { 

    @Autowired
    private DataSource dataSource;

    @Override
    public List<String> recommendNextVideo(String bv) { // Accepted
        if (!isVideoExist(bv)) return null;

        String sql = "SELECT v.bv, COUNT(*) as shared_viewers " +
                    "FROM users_watch uw1 " +
                    "JOIN users_watch uw2 ON uw1.mid = uw2.mid AND uw1.bv <> uw2.bv " +
                    "JOIN videos v ON uw2.bv = v.bv " +
                    "WHERE uw1.bv = ? AND v.deleted = FALSE AND v.reviewed = TRUE " +
                    "GROUP BY v.bv " +
                    "ORDER BY shared_viewers DESC " +
                    "LIMIT 5";

        HashMap<String, Integer> recommendedVideos = new HashMap<>();

        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, bv);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                recommendedVideos.put(rs.getString("bv"), rs.getInt("shared_viewers"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("recommendNextVideo query failed", e);
        }

        // return top 5 bv with most same watch users
        return recommendedVideos.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
            
    }

    @Override
    public List<String> generalRecommendations(int pageSize, int pageNum) { // Accepted
        if (pageSize <= 0 || pageNum <= 0) {
            return null;
        }
    
        String sql = "SELECT v.bv,\n" +
                "       (CASE\n" +
                "            WHEN v.view_times > 0 THEN \n" +
                "                (CAST(ARRAY_LENGTH(v.like, 1) AS FLOAT) / v.view_times +\n" +
                "                 CAST(ARRAY_LENGTH(v.coin, 1) AS FLOAT) / v.view_times +\n" +
                "                 CAST(ARRAY_LENGTH(v.collect, 1) AS FLOAT) / v.view_times +\n" +
                "                 COALESCE(d.danmu_count / v.view_times, 0) +\n" +
                "                 COALESCE(w.avg_finish / v.duration, 0))\n" +
                "            ELSE 0\n" +
                "        END) AS recommendation_score\n" +
                "FROM videos v\n" +
                "LEFT JOIN (SELECT bv, COUNT(id) AS danmu_count\n" +
                "           FROM danmus\n" +
                "           GROUP BY bv) d ON v.bv = d.bv\n" +
                "LEFT JOIN (SELECT bv, AVG(view_time) AS avg_finish\n" +
                "           FROM users_watch\n" +
                "           GROUP BY bv) w ON v.bv = w.bv\n" +
                "WHERE NOT v.deleted\n" +
                "ORDER BY recommendation_score DESC\n" +
                "LIMIT ? OFFSET ?;\n";

            List<String> recommendations = new ArrayList<>();
        
            try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
        
                stmt.setInt(1, pageSize);
                stmt.setInt(2, (pageNum - 1) * pageSize);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    recommendations.add(rs.getString("bv"));
                }
            } catch (SQLException e) {
                throw new RuntimeException("generalRecommendations query failed", e);
            }
        
            return recommendations;
        }

    @Override
    public List<String> recommendVideosForUser(AuthInfo auth, int pageSize, int pageNum) { // Wrong Answer
        if (isAuthValid(auth) == 0 || pageSize <= 0 || pageNum <= 0) {
            return null;
        }

        // 获取用户的关注者和被关注者的视频列表，并获取视频所有者的等级信息
        String interestSql = "WITH Friends AS (\n" +
                        "    SELECT uf1.follower_mid AS mid\n" +
                        "    FROM users_follow uf1\n" +
                        "    INNER JOIN users_follow uf2 ON uf1.follower_mid = uf2.followee_mid AND uf1.followee_mid = uf2.follower_mid\n" +
                        "    WHERE uf1.deleted = FALSE AND uf2.deleted = FALSE AND uf1.follower_mid = ?\n" +
                        "),\n" +
                        "WatchedVideos AS (\n" +
                        "    SELECT uw.bv\n" +
                        "    FROM users_watch uw\n" +
                        "    INNER JOIN Friends ON uw.mid = Friends.mid\n" +
                        "    GROUP BY uw.bv\n" +
                        "),\n" +
                        "UnwatchedVideos AS (\n" +
                        "    SELECT bv\n" +
                        "    FROM WatchedVideos\n" +
                        "    WHERE bv NOT IN (SELECT bv FROM users_watch WHERE mid = ?)\n" +
                        "),\n" +
                        "SortedVideos AS (\n" +
                        "    SELECT uv.bv\n" +
                        "    FROM UnwatchedVideos uv\n" +
                        "    INNER JOIN videos v ON uv.bv = v.bv\n" +
                        "    INNER JOIN users u ON v.owner_mid = u.mid\n" +
                        "    WHERE v.deleted = FALSE AND v.reviewed = TRUE AND v.public_time < ?\n" +
                        "    ORDER BY (\n" +
                        "        SELECT COUNT(*)\n" +
                        "        FROM users_watch\n" +
                        "        WHERE bv = uv.bv AND mid IN (SELECT mid FROM Friends)\n" +
                        "    ) DESC, u.level DESC, v.public_time\n" +
                        ")\n" +
                        "SELECT bv FROM SortedVideos\n" ;
                        //"LIMIT ? OFFSET ?";

        List<String> recommendations = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(interestSql)) {

            long userId = getMidByAuth(auth);
            stmt.setLong(1, userId);
            stmt.setLong(2, userId);
            stmt.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis()));

        ResultSet rs = stmt.executeQuery();

        while (rs.next()) {
            recommendations.add(rs.getString("bv"));
        }

        } catch (SQLException e) {
            throw new RuntimeException("recommendVideosForUser query failed", e);
        }

        if (recommendations.isEmpty()){
            return generalRecommendations(pageSize, pageNum);
        }

        return recommendations.stream()
                .skip((long) (pageNum - 1) * pageSize)
                .limit(pageSize)
                .collect(Collectors.toList());
    }

    @Override
    public List<Long> recommendFriends(AuthInfo auth, int pageSize, int pageNum) { // Wrong Answer

        if (isAuthValid(auth) == 0 || pageSize <= 0 || pageNum <= 0) {
            return null;
        }

        // SQL 查询以找出推荐的朋友
        String sql = "SELECT DISTINCT v.bv, COUNT(uw.mid) AS friend_watches, u.level, v.public_time\n" +
                "FROM videos v\n" +
                "JOIN users_watch uw ON v.bv = uw.bv\n" +
                "JOIN users u ON v.owner_mid = u.mid\n" +
                "WHERE uw.mid IN (\n" +
                "    SELECT uf.followee_mid \n" +
                "    FROM users_follow uf \n" +
                "    WHERE uf.follower_mid = ?\n" +
                "    AND uf.followee_mid IN (\n" +
                "        SELECT uf.follower_mid \n" +
                "        FROM users_follow uf \n" +
                "        WHERE uf.followee_mid = ?\n" +
                "    )\n" +
                ")\n" +
                "AND v.bv NOT IN (\n" +
                "    SELECT bv \n" +
                "    FROM users_watch \n" +
                "    WHERE mid = ?\n" +
                ")\n" +
                "GROUP BY v.bv, u.level, v.public_time\n" +
                "ORDER BY friend_watches DESC, u.level DESC, v.public_time DESC\n" +
                "LIMIT ? OFFSET ?;";

        List<Long> friendRecommendations = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            long userId = getMidByAuth(auth);
            stmt.setLong(1, userId);
            stmt.setLong(2, userId);
            stmt.setLong(3, userId);
            stmt.setInt(4, pageSize);
            stmt.setInt(5, (pageNum - 1) * pageSize);

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                //friendRecommendations.add(rs.getLong("mid"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("recommendFriends query failed", e);
        }

        return friendRecommendations;
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
                return rs.getInt("mid");
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
                }if (rs.getString("identity").equals("SUPERUSER")) {
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
    
}
