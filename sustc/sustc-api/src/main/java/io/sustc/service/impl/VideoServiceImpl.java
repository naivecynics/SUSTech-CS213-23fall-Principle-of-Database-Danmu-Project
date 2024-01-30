package io.sustc.service.impl;


import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.Timestamp;

import org.springframework.stereotype.Service;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.PostVideoReq;
import io.sustc.service.VideoService;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class VideoServiceImpl implements VideoService{

    @Autowired
    private DataSource dataSource;



    @Override
    public String postVideo(AuthInfo auth, PostVideoReq req) {
        if (isAuthValid(auth) == 0) return null;
        if (!isReqValid(req)) return null;

        String sqlCheck = "SELECT bv FROM videos WHERE title = ? AND owner_mid = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sqlCheck)
        ) {
            stmt.setString(1, req.getTitle());
            stmt.setLong(2, getMidByAuth(auth));

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Check if video exists when posting video failed.", e);
        }

        String sql = "INSERT INTO videos (title, description, duration, commit_time ,public_time, bv) VALUES (?,?,?,?,?,?)";

        String bv = UUID.randomUUID().toString().replace("-", "");

        try(
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setString(1, req.getTitle());
            stmt.setString(2, req.getDescription());
            stmt.setFloat(3, req.getDuration());
            stmt.setTimestamp(4, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setTimestamp(5, req.getPublicTime());
            stmt.setString(6, bv);

            int affectedRows = stmt.executeUpdate();

            if (affectedRows > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        return bv;
                    }
                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            }
            else return null;

        } catch (SQLException e) {
            throw new RuntimeException("Post video failed", e);
        }
        return bv;
    }

    

    @Override
    public boolean deleteVideo(AuthInfo auth, String bv) {
        int auth_identity = isAuthValid(auth);
        if (auth_identity == 0) return false;
        else if (!isDeleteValid(auth_identity, getMidByAuth(auth), bv)) return false;

        String sql = "UPDATE videos SET deleted = true WHERE bv = ?";
        
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setString(1, bv);

            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Delete video failed", e);
        }

    }

    boolean isDeleteValid (int identity, long mid, String bv) {
        String sql = "SELECT bv, owner_mid FROM videos WHERE bv = ?";

        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, bv);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (identity == 2) return true;
                else if (identity == 1 && rs.getLong("owner_mid") == mid) return true;
                else return false;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Auth is not valid", e);
        }
        return true;
    }

    @Override
    public boolean updateVideoInfo(AuthInfo auth, String bv, PostVideoReq req) {

        if (isAuthValid(auth) == 0) return false;
        if (!isReqValid(req)) return false;


        // check if req is valid
        String sqlCheck = "SELECT owner_mid, duration, title, description FROM videos WHERE bv = ?";
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sqlCheck)
        ) {
            stmt.setString(1, bv);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (rs.getLong("owner_mid") != getMidByAuth(auth)) {
                    return false;
                }
                if (rs.getLong("duration") != (long)req.getDuration()) {
                    return false;
                }
                if (Objects.equals(req.getTitle(), rs.getString("title")) && Objects.equals(req.getDescription(), rs.getString("description"))) {
                    return false;
                }
            }
            else {
                return false;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Check if video match owner when updating video failed.", e);
        }

        // execute update
        String sql = "UPDATE videos SET title = ?, description = ?, duration = ?,  reviewed = ? WHERE bv = ?";

        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)
        ) {
            stmt.setString(1, req.getTitle());
            stmt.setString(2, req.getDescription());
            stmt.setLong(3, (long) req.getDuration());
            stmt.setBoolean(4, false);
            stmt.setString(5, bv);
            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Executing update video failed", e);
        }

    }

    @Override
    public List<String> searchVideo(AuthInfo auth, String keywords, int pageSize, int pageNum) { // Wrong Answer
        // 验证参数
        int id = isAuthValid(auth);
        if (id == 0 || keywords.equals("null") || keywords.isEmpty() || pageSize <= 0 || pageNum <= 0) {
            return null;
        }
        // 如果是管理员，可以看全部视频
        // 如果是普通用户，只能看到已审核的视频和自己的未审核的视频

        // publish_time?

        String sql = "SELECT bv, title, description, owner_name, view_times,\n" +
                "       ((LENGTH(LOWER(title)) - LENGTH(REPLACE(LOWER(title), ?, ''))) / LENGTH(?) +\n" +
                "        (LENGTH(LOWER(description)) - LENGTH(REPLACE(LOWER(description), ?, ''))) / LENGTH(?) +\n" +
                "        (LENGTH(LOWER(owner_name)) - LENGTH(REPLACE(LOWER(owner_name), ?, ''))) / LENGTH(?)) as cnt\n" +
                "FROM videos \n" +
                "WHERE (LOWER(title) LIKE ? ESCAPE '\\' OR LOWER(description) LIKE ? ESCAPE '\\' OR LOWER(owner_name) LIKE ? ESCAPE '\\' )\n" +
                "      AND (reviewed = true AND deleted = false AND public_time < ?)\n" +
                "      OR (owner_mid = ? AND reviewed = false)\n" +
                "      OR (2 = ?)";
        

        String[] keywordArray = keywords.toLowerCase().split(" ");
        HashMap<String, Integer> filteredVideos = new HashMap<String, Integer>();
        HashMap<String, Integer> videoView = new HashMap<String, Integer>();

        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {


            for (String keyword : keywordArray) {
                if (keyword.isEmpty() || keyword.equals(" ")) continue;
                // 防止sql注入，转义特殊字符
                keyword = keyword.replace("%", "\\%").replace("_", "\\_");
                String likeKeyword = "%" + keyword + "%";
                stmt.setString(1, keyword);
                stmt.setString(2, keyword);
                stmt.setString(3, keyword);
                stmt.setString(4, keyword);
                stmt.setString(5, keyword);
                stmt.setString(6, keyword);
                stmt.setString(7, likeKeyword);
                stmt.setString(8, likeKeyword);
                stmt.setString(9, likeKeyword);
                stmt.setTimestamp(10, Timestamp.valueOf(LocalDateTime.now()));
                stmt.setLong(11, getMidByAuth(auth));
                stmt.setInt(12, id);
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    if (rs.getInt("cnt") == 0) continue;
                    // 添加视频到列表，如果它尚未添加
                    if (!videoView.containsKey(rs.getString("bv"))) {
                        videoView.put(rs.getString("bv"), rs.getInt("view_times"));
                    }
                    if (!filteredVideos.containsKey(rs.getString("bv"))) {
                        filteredVideos.put(rs.getString("bv"), rs.getInt("cnt"));
                    }
                    else {
                        filteredVideos.put(rs.getString("bv"), filteredVideos.get(rs.getString("bv")) + rs.getInt("cnt"));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Video search failed", e);
        }

        // 计算bv出现次数并排序
        // 如果出现次数相同，按照观看次数从大到小排序
        List<String> sortedVideos = filteredVideos.entrySet().stream()
                .sorted((a, b) -> {
                    if (Objects.equals(a.getValue(), b.getValue())) {
                        return videoView.get(b.getKey()).compareTo(videoView.get(a.getKey()));
                    }
                    else {
                        return b.getValue() - a.getValue();
                    }
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        if (sortedVideos.size() / pageSize < pageNum - 1) {
            return new ArrayList<>();
        }

        return sortedVideos.subList((pageNum - 1) * pageSize, Math.min(pageNum * pageSize, sortedVideos.size()));
    }

    @Override
    public double getAverageViewRate(String bv) { // Accepted
        // Validate BV
        if (bv == null || bv.isEmpty() || !isVideoExist(bv)) {
            return -1; // BV is invalid or video not found
        }
    
        String sqlViews = "SELECT view_times, duration FROM videos WHERE bv = ?";
        String sqlDuration = "SELECT view_time FROM users_watch WHERE bv = ?";
    
        double duration = 0;
        double total_duration = 0;
        long viewCount = 0;

        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmtViews = conn.prepareStatement(sqlViews);) {

            // Get total view times and dutation
            stmtViews.setString(1, bv);
            ResultSet rsViews = stmtViews.executeQuery();
            if (rsViews.next()) {
                viewCount = rsViews.getLong("view_times");
                duration = rsViews.getFloat("duration");
            }

        } catch (SQLException e) {
            throw new RuntimeException("Get average view rate failed", e);
        }

        try(Connection conn = dataSource.getConnection();
            PreparedStatement stmtDuration = conn.prepareStatement(sqlDuration)) {

            // Get total view time
            stmtDuration.setString(1, bv);
            ResultSet rsDuration = stmtDuration.executeQuery();
            while (rsDuration.next()) {
                total_duration += rsDuration.getFloat("view_time");
            }
        } catch (SQLException e) {
            throw new RuntimeException("Get average view rate failed", e);
        }

        // Calculate and return average view rate
        return total_duration / (viewCount * duration);
    }
    

    @Override
    public Set<Integer> getHotspot(String bv) { // Accepted

        // Validate BV
        if (bv == null || bv.isEmpty() || !isVideoExist(bv)) {
            return null; // BV is invalid or video not found
        }
    
        String sql = "SELECT time FROM danmus WHERE bv = ?";
        String sqlDuration = "SELECT duration FROM videos WHERE bv = ?";
    
        try (
            Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql);
            PreparedStatement stmtDuration = conn.prepareStatement(sqlDuration)
        ) {

            // Get video duration
            stmtDuration.setString(1, bv);
            long duration = 0;
            ResultSet rsDuration = stmtDuration.executeQuery();
            if (rsDuration.next()) {
                duration = rsDuration.getLong("duration");
            }
    
            // Handle case where no one has watched the video
            if (duration == 0) {
                return null;
            }
    
            // Get danmus
            stmt.setString(1, bv);
            ResultSet rs = stmt.executeQuery();
            List<Integer> danmuList = new ArrayList<>();
            while (rs.next()) {
                danmuList.add(rs.getInt("time"));
            }

            if (danmuList.isEmpty()) {
                return null;
            }

            // 先计算每个10秒组的弹幕数量
            Map<Long, Long> counts = danmuList.stream()
                    .collect(Collectors.groupingBy(time -> (long) (time / 10), Collectors.counting()));

            // 找出最大出现次数
            long maxCount = counts.values().stream()
                    .max(Comparator.naturalOrder())
                    .orElse(0L);

            // 收集所有出现次数为最大值的组，并按照出现顺序排序
            Set<Long> maxGroups = counts.entrySet().stream()
                    .filter(entry -> entry.getValue() == maxCount)
                    .map(Map.Entry::getKey)
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            return maxGroups.stream()
                    .map(group -> (int) (group * 1))
                    .collect(Collectors.toSet());

        } catch (SQLException e) {
            throw new RuntimeException("Get hotspot failed", e);
        }
    }

    @Override
    public boolean reviewVideo(AuthInfo auth, String bv) {

        if (isAuthValid(auth) != 2) return false;
        if (!isVideoExist(bv)) return false;

        String sqlAlreadyReviewed = "SELECT reviewed FROM videos WHERE bv = ?";

        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sqlAlreadyReviewed)) {
            stmt.setString(1, bv);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (rs.getBoolean("reviewed")) return false;
            }

            } catch (SQLException e) {
                throw new RuntimeException("Check if video is already reviewed failed", e);
            }

         // Review the video
        // update reviewed, review_time = current, reviewer
        String sql = "UPDATE videos SET reviewed = true, review_time = ?, reviewer = ? WHERE bv = ?";
        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            stmt.setLong(2, auth.getMid());
            stmt.setString(3, bv);

            stmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Review video failed", e);
        }

    }

    @Override
    public boolean coinVideo(AuthInfo auth, String bv) {
        int id = isAuthValid(auth);
        long mid = getMidByAuth(auth);
        if (id == 0) return false;
        if (!isVideoExist(bv)) return false;
        if (!ifVideoVisible(mid, bv, id)) return false;

        String sqlUser = "UPDATE users SET coin = coin - 1 WHERE mid = ?";
        String sqlSelectVideo = "SELECT coin FROM videos WHERE bv = ?";
        String sqlVideo = "UPDATE videos SET coin = ? WHERE bv = ?";
        String sqlCheckCoin = "SELECT coin FROM users WHERE mid = ?";

        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmtVideo = conn.prepareStatement(sqlVideo);
            PreparedStatement stmtSelectVideo = conn.prepareStatement(sqlSelectVideo);
            PreparedStatement stmtUser = conn.prepareStatement(sqlUser);
            PreparedStatement stmtCheckCoin = conn.prepareStatement(sqlCheckCoin);

        ) {

            stmtVideo.setString(2, bv);
            stmtUser.setLong(1, mid);
            stmtCheckCoin.setLong(1, mid);
            stmtSelectVideo.setString(1, bv);

            ResultSet rs = stmtCheckCoin.executeQuery();
            if (rs.next()) {
                if (rs.getInt("coin") <= 0) return false;
            }
            ResultSet rs3 = stmtSelectVideo.executeQuery();
            ArrayList<Long> coinList = new ArrayList<Long>();
            if (rs3.next()) {
                String coinString = rs3.getString("coin");
                if (coinString != null) {
                    String[] coinArray = coinString.split(",");
                    for (String coin : coinArray) {
                        coin = fixString(coin);
                        long coiner = Long.parseLong(coin);
                        if (coiner == mid) return false;
                        coinList.add(coiner);
                    }
                }
            }

            coinList.add(mid);
            stmtVideo.setArray (1, conn.createArrayOf("bigint", coinList.toArray()));

            stmtVideo.executeUpdate();
            stmtUser.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new RuntimeException("Coin video failed", e);
        }
    }

    @Override
    public boolean likeVideo(AuthInfo auth, String bv) {

        int id = isAuthValid(auth);
        long mid = getMidByAuth(auth);
        if (bv.equals("null") || bv.isEmpty()) return false;
        if (id == 0) return false;
        if (!isVideoExist(bv)) return false;
        if (!ifVideoVisible(mid, bv, id)) return false;

        String sqlSelect = "SELECT owner_mid, \"like\" FROM videos WHERE bv = ?";
        String sqlUpdate = "UPDATE videos SET like = ? WHERE bv = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmtSelect = conn.prepareStatement(sqlSelect);
             PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)
        ) {
            stmtSelect.setString(1, bv);
            stmtUpdate.setString(2, bv);

            ResultSet rs = stmtSelect.executeQuery();
            ArrayList<Long> Liker = new ArrayList<Long>();
            boolean tag = false;
            if (rs.next()) {
                if (rs.getLong("owner_mid") == mid) return false;
                String likeString = rs.getString("like");
                if (likeString != null) {
                    String[] likeArray = likeString.split(",");
                    for (String like : likeArray) {
                        like = fixString(like);
                        long liker = Long.parseLong(like);
                        if (liker == mid) {
                            tag = true;
                        }
                        else {
                            Liker.add(liker);
                        }
                    }
                }
            }
            if (tag) {
                stmtUpdate.setArray(1, conn.createArrayOf("bigint", Liker.toArray()));
            }
            else {
                Liker.add (mid);
                stmtUpdate.setArray(1, conn.createArrayOf("bigint", Liker.toArray()));
            }
            if (tag) return false;
            else return true;
        } catch (SQLException e) {
            throw new RuntimeException("Like video failed", e);
        }
    }

    @Override
    public boolean collectVideo(AuthInfo auth, String bv) {
        int id = isAuthValid(auth);
        long mid = getMidByAuth(auth);
        if (id == 0) return false;
        if (!isVideoExist(bv)) return false;
        if (!ifVideoVisible(mid, bv, id)) return false;

        String sqlSelect = "SELECT owner_mid, collect FROM videos WHERE bv = ?";
        String sqlUpdate = "UPDATE videos SET collect = ? WHERE bv = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmtSelect = conn.prepareStatement(sqlSelect);
             PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)
        ) {
            stmtSelect.setString(1, bv);
            stmtUpdate.setString(2, bv);

            ResultSet rs = stmtSelect.executeQuery();
            ArrayList<Long> Liker = new ArrayList<Long>();
            boolean tag = false;
            if (rs.next()) {
                if (rs.getLong("owner_mid") == mid) return false;
                String likeString = rs.getString("collect");
                if (likeString != null) {
                    String[] likeArray = likeString.split(",");
                    for (String like : likeArray) {
                        like = fixString(like);
                        long liker = Long.parseLong(like);
                        if (liker == mid) {
                            tag = true;
                        }
                        else {
                            Liker.add(liker);
                        }
                    }
                }
            }
            if (tag) {
                stmtUpdate.setArray(1, conn.createArrayOf("bigint", Liker.toArray()));
            }
            else {
                Liker.add (mid);
                stmtUpdate.setArray(1, conn.createArrayOf("bigint", Liker.toArray()));
            }
            if (tag) return false;
            else return true;
        } catch (SQLException e) {
            throw new RuntimeException("Collect video failed", e);
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
                //if (rs.getString("password") == null || rs.getString("password").isEmpty()) return 0;
                if (byPassword && (auth.getPassword() == null || !auth.getPassword().equals(rs.getString("password")))) {
                    log.info("Wrong password\n");
                    return 0;
                }
                if (rs.getString("identity").equals("SUPERUSER")) {
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

    private static boolean isReqValid(PostVideoReq req) {
        if (req.getTitle() == null || req.getTitle().isEmpty()) {
            return false;
        }
        if (req.getPublicTime() == null) {
            return false;
        }
        if (req.getDuration() < 10) {
            return false;
        }
        if (req.getPublicTime().before(Timestamp.valueOf(LocalDateTime.now()))) {
             return false;
        }
        return true;
    }

    private boolean isVideoExist(String bv) {
        String sql = "SELECT title, owner_name, description, deleted, duration FROM videos WHERE bv = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, bv);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (rs.getString("title") == null || rs.getString("owner_name") == null
                        || rs.getString("description") == null || rs.getInt("duration") == 0) {
                    return false;
                }
                return !rs.getBoolean("deleted");
            }
            return false;
        } catch (SQLException e) {
            throw new RuntimeException("Check if video exists failed", e);
        }
    }

    private boolean ifVideoVisible (long mid, String bv, int id) {
        if (id == 2) return true;
        else { // 普通用户
            String sql = "SELECT owner_mid, deleted, reviewed FROM videos WHERE bv = ?";

            try ( 
                Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)
            ) {
                stmt.setString(1, bv);

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    if (rs.getBoolean("deleted")) return false;
                    if (!rs.getBoolean("reviewed")){
                        if (rs.getString("owner_mid") == null) return false;
                        if (rs.getLong("owner_mid") == mid) return true;
                        else return false;
                    }
                }
                return true;
            } catch (SQLException e) {
                throw new RuntimeException("Check if video is visible failed", e);
            }
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

}
