package io.sustc.service.impl;

import io.sustc.dto.DanmuRecord;
import io.sustc.dto.UserRecord;
import io.sustc.dto.VideoRecord;
import io.sustc.service.DatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

/**
 * It's important to mark your implementation class with {@link Service} annotation.
 * As long as the class is annotated and implements the corresponding interface, you can place it under any package.
 */
@Service
@Slf4j
public class DatabaseServiceImpl implements DatabaseService {

    /**
     * Getting a {@link DataSource} instance from the framework, whose connections are managed by HikariCP.
     * <p>
     * Marking a field with {@link Autowired} annotation enables our framework to automatically
     * provide you a well-configured instance of {@link DataSource}.
     * Learn more: <a href="https://www.baeldung.com/spring-dependency-injection">Dependency Injection</a>
     */
    @Autowired
    private DataSource dataSource;

    @Override
    public List<Integer> getGroupMembers() {
        //TODO: replace this with your own student IDs in your group
        return List.of(12213009);
    }

    @Override
    public void importData(
            List<DanmuRecord> danmuRecords,
            List<UserRecord> userRecords,
            List<VideoRecord> videoRecords
    ) {

        // user
        String sqlUser = "INSERT INTO users (mid, name, sex, birthday, level, sign, identity, password, qq, wechat, coin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String sqlFollowing = "INSERT INTO users_follow (follower_mid, followee_mid) VALUES (?, ?)";
        // video
        String sqlVideo = "INSERT INTO videos (bv, title, owner_mid, owner_name, commit_time, review_time, public_time, duration, description, reviewer, coin, \"like\", collect, view_times) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String sqlView = "INSERT INTO users_watch (bv, mid, view_time) VALUES (?,?,?)";
        // danmu
        String sqlDanmu = "INSERT INTO danmus (bv, mid, time, content, post_time, liked_by) VALUES (?,?,?,?,?,?)";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (
                    PreparedStatement pstmt = conn.prepareStatement(sqlUser);
                    PreparedStatement pstmt2 = conn.prepareStatement(sqlFollowing);
            ) {
                int cnt = 0;
                for (UserRecord user : userRecords) {
                    pstmt.setLong(1, user.getMid());
                    pstmt.setString(2, user.getName());
                    pstmt.setString(3, user.getSex());
                    pstmt.setString(4, user.getBirthday());
                    pstmt.setShort(5, user.getLevel());
                    pstmt.setString(6, user.getSign());
                    pstmt.setString(7, user.getIdentity().name());
                    pstmt.setString(8, user.getPassword());
                    pstmt.setString(9, user.getQq());
                    pstmt.setString(10, user.getWechat());
                    pstmt.setInt(11, user.getCoin());
                    pstmt.addBatch();
                    if (++cnt % 5000 == 0)  pstmt.executeBatch();

                    int cnt2 = 0;
                    for (Long following : user.getFollowing()) {
                        pstmt2.setLong(1, user.getMid());
                        pstmt2.setLong(2, following);
                        pstmt2.addBatch();
                        if (++cnt2 % 100 == 0) pstmt2.executeBatch();
                    }
                    pstmt2.executeBatch();

                    System.out.print ("\rUser  Progress: " + cnt + "/" + userRecords.size());
                }
                pstmt.executeBatch();
                System.out.print ("\rUser Progress: " + cnt + "/" + userRecords.size() + "\tFINISHED!\n");

            } catch (SQLException e) {
                //conn.rollback();
                throw new SQLException("Fail to import user", e);
            }

            try (
                    PreparedStatement pstmt3 = conn.prepareStatement(sqlVideo);
                    PreparedStatement pstmt4 = conn.prepareStatement(sqlView);
            ){
                int cnt3 = 0;
                for (VideoRecord video : videoRecords) {
                    pstmt3.setString(1, video.getBv());
                    pstmt3.setString(2, video.getTitle());
                    pstmt3.setLong(3, video.getOwnerMid());
                    pstmt3.setString(4, video.getOwnerName());
                    pstmt3.setTimestamp(5, video.getCommitTime());
                    pstmt3.setTimestamp(6, video.getReviewTime());
                    pstmt3.setTimestamp(7, video.getPublicTime());
                    pstmt3.setFloat(8, video.getDuration());
                    pstmt3.setString(9, video.getDescription());
                    pstmt3.setLong(10, video.getReviewer());
                    pstmt3.setArray(11, conn.createArrayOf("bigint", Arrays.stream(video.getCoin()).boxed().toArray(Long[]::new)));
                    pstmt3.setArray(12, conn.createArrayOf("bigint", Arrays.stream(video.getLike()).boxed().toArray(Long[]::new)));
                    pstmt3.setArray(13, conn.createArrayOf("bigint", Arrays.stream(video.getFavorite()).boxed().toArray(Long[]::new)));
                    pstmt3.setLong(14, video.getViewerMids().length);
                    pstmt3.addBatch();
                    if (++cnt3 % 10 == 0) pstmt3.executeBatch();

                    int cnt4 = 0;
                    for (int i = 0; i < video.getViewerMids().length; i++) {
                        pstmt4.setString(1, video.getBv());
                        pstmt4.setLong(2, video.getViewerMids()[i]);
                        pstmt4.setFloat(3, video.getViewTime()[i]);
                        pstmt4.addBatch();
                        if (++cnt4 % 2000 == 0) pstmt4.executeBatch();
                    }
                    pstmt4.executeBatch();
                    System.out.print ("\rVideo Progress: " + cnt3 + "/" + videoRecords.size());
                }
                pstmt3.executeBatch();
                System.out.print ("\rVideo Progress: " + cnt3 + "/" + videoRecords.size() + "\tFINISHED!\n");

            } catch (SQLException e) {
                conn.rollback();
                throw new SQLException("Fail to import video", e);
            }

            try (
                    PreparedStatement pstmt5 = conn.prepareStatement(sqlDanmu);
            ) {
                int cnt5 = 0;
                for (DanmuRecord danmu : danmuRecords) {
                    pstmt5.setString(1, danmu.getBv());
                    pstmt5.setLong(2, danmu.getMid());
                    pstmt5.setFloat(3, danmu.getTime());
                    pstmt5.setString(4, danmu.getContent());
                    pstmt5.setTimestamp(5, danmu.getPostTime());
                    pstmt5.setArray(6, conn.createArrayOf("bigint", Arrays.stream(danmu.getLikedBy()).boxed().toArray(Long[]::new)));
                    pstmt5.addBatch();
                    if (++cnt5 % 100 == 0) pstmt5.executeBatch();
                    System.out.print ("\rDanmu Progress: " + cnt5 + "/" + danmuRecords.size());
                }
                pstmt5.executeBatch();
                System.out.print ("\rDanmu Progress: " + cnt5 + "/" + danmuRecords.size() + "\tFINISHED!\n");


            } catch (SQLException e) {
                conn.rollback();
                throw new SQLException("Fail to import danmu", e);
            }

            conn.commit();
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /*
     * The following code is just a quick example of using jdbc datasource.
     * Practically, the code interacts with database is usually written in a DAO layer.
     *
     * Reference: [Data Access Object pattern](https://www.baeldung.com/java-dao-pattern)
     */

    @Override
    public void truncate() {
        // You can use the default truncate script provided by us in most cases,
        // but if it doesn't work properly, you may need to modify it.

        // String sql = "DO $$\n" +
        //         "DECLARE\n" +
        //         "    tables CURSOR FOR\n" +
        //         "        SELECT sum\n" +
        //         "        FROM tables\n" +
        //         "        WHERE schemaname = 'public';\n" +
        //         "BEGIN\n" +
        //         "    FOR t IN tables\n" +
        //         "    LOOP\n" +
        //         "        EXECUTE 'TRUNCATE TABLE ' || QUOTE_IDENT(t.tablename) || ' CASCADE;';\n" +
        //         "    END LOOP;\n" +
        //         "END $$;\n";

        // modified
        String sql = "DO $$ " +
                "DECLARE r RECORD; " +
                "BEGIN " +
                "FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public') " +
                "LOOP " +
                "EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' CASCADE;'; " +
                "END LOOP; " +
                "END $$;";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Integer sum(int a, int b) {
        String sql = "SELECT ?+?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, a);
            stmt.setInt(2, b);
            log.info("SQL: {}", stmt);

            ResultSet rs = stmt.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
