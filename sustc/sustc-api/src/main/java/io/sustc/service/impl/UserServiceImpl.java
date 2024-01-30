package io.sustc.service.impl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import java.sql.Connection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import io.sustc.dto.AuthInfo;
import io.sustc.dto.RegisterUserReq;
import io.sustc.dto.UserInfoResp;
import io.sustc.service.UserService;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class UserServiceImpl implements UserService{

    @Autowired
    private DataSource dataSource;

    private static long maxMid = -1;

    /**
     * Registers a new user.
     * {@code password} is a mandatory field, while {@code qq} and {@code wechat} are optional
     * <a href="https://openid.net/developers/how-connect-works/">OIDC</a> fields.
     *
     * @param req information of the new user
     * @return the new user's {@code mid}
     * @apiNote You may consider the following corner cases:
     * <ul>
     *   <li>{@code password} or {@code name} or {@code sex} in {@code req} is null or empty</li>
     *   <li>{@code birthday} in {@code req} is valid (not null nor empty) while it's not a birthday (X月X日)</li>
     *   <li>there is another user with same {@code name} or {@code qq} or {@code wechat} in {@code req}</li>
     * </ul>
     * If any of the corner case happened, {@code -1} shall be returned.
     */
    @Override
    public long register(RegisterUserReq req) {
        if (UserServiceImpl.maxMid == -1) {
            String sql = "SELECT MAX(mid) FROM users";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    UserServiceImpl.maxMid = rs.getLong(1) + 1;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to retrieve max mid.", e);
            }
        }
        if (UserServiceImpl.maxMid == -1) return -1;
        String sql = "INSERT INTO users (mid, name, sex, birthday, sign, password, qq, wechat) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        if (isUserExist(getMidByName(req.getName()))) return -4;
        
        if (req.getPassword() == null || req.getName() == null || req.getSex() == null || req.getName().isEmpty() || req.getPassword().isEmpty()) {
            return -1;
        }

        if (req.getBirthday() != null && !isValidDate(req.getBirthday())) {
            return -1;
        }

        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {

            long midValue = -3;

            synchronized (UserServiceImpl.class) {
                midValue = UserServiceImpl.maxMid++;
            }

            stmt.setLong(1, midValue);
            stmt.setString(2, req.getName());
            stmt.setString(3, req.getSex().toString());

            if (req.getBirthday() == null) {
                stmt.setNull(4, java.sql.Types.VARCHAR);
            } else {
                stmt.setString(4, req.getBirthday());
            }

            if (req.getSign() == null) {
                stmt.setNull(5, java.sql.Types.VARCHAR);
            } else {
                stmt.setString(5, req.getSign());
            }
            stmt.setString(6, req.getPassword());

            if (req.getQq() == null) {
                stmt.setNull(7, java.sql.Types.VARCHAR);
            } else {
                stmt.setString(7, req.getQq());
            }

            if (req.getWechat() == null) {
                stmt.setNull(8, java.sql.Types.VARCHAR);
            } else {
                stmt.setString(8, req.getWechat());
            }

            int affectedRows = stmt.executeUpdate();
    
            if (affectedRows == 0) {
                return -1;
            }
    
            return midValue;

        } catch (SQLException e) {
            throw new RuntimeException("Register failed.",e);
        }
    }

    public long getMidByName(String name) {
        String sql = "SELECT mid FROM users WHERE name = ?";
        
        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("mid");
                } else {
                    return -1;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to retrieve mid by name.", e);
        }
    }

    private static final int[] MONTHS = {0, 31,29,31,30,31,30,31,31,30,31,30,31};
    
    boolean isValidDate(String date) {
        String pattern = "^(0?[1-9]|1[0-2])月(0?[1-9]|[12][0-9]|3[01])日$";
        Pattern r = Pattern.compile(pattern);
        Matcher m = r.matcher(date);
        if (!m.find()) {
            return false;
        }
        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        return day >= 1 && day <= MONTHS[month];
    }


        /**
     * Deletes a user.
     *
     * @param auth indicates the current user
     * @param mid  the user to be deleted
     * @return operation success or not
     * @apiNote You may consider the following corner cases:
     * <ul>
     *   <li>{@code mid} is invalid (<= 0)</li>
     *   <li>the {@code auth} is invalid
     *     <ul>
     *       <li>both {@code qq} and {@code wechat} are non-empty while they do not correspond to same user</li>
     *       <li>{@code mid} is invalid while {@code qq} and {@code wechat} are both invalid (empty or not found)</li>
     *     </ul>
     *   </li>
     *   <li>the current user is a regular user while the {@code mid} is not his/hers</li>
     *   <li>the current user is a super user while the {@code mid} is not his/hers</li>
     * </ul>
     * If any of the corner case happened, {@code false} shall be returned.
     */
    @Override
    public boolean deleteAccount(AuthInfo auth, long mid) {
        int id = isAuthValid(auth);
        int id_deleted = getIdByMid(mid);
        long Mid = getMidByAuth(auth);
        if (id == 0) return false;
        if ((id == 1 && Mid != mid) || (id == 2 && id_deleted == 2 && mid != Mid)) return false;

        String sql = "UPDATE users SET deleted = true WHERE mid = ?";
        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, mid);
            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                return false;
            } else {
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Delete account failed.", e);
        }
    }

    private int getIdByMid(long mid) {
        String sql = "SELECT identity FROM users WHERE mid = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, mid);

            ResultSet rs = stmt.executeQuery();
            if (rs.next()){
                if (rs.getString("identity").equals("SUPERUSER")) return 2;
                else if (rs.getString("identity").equals("USER")) return 1;
                else return 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Delete account failed.", e);
        }
        return 0;
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

    /**
     * Follow the user with {@code mid}.
     * If that user has already been followed, unfollow the user.
     *
     * @param auth        the authentication information of the follower
     * @param followeeMid the user who will be followed
     * @return operation success or not
     * @apiNote You may consider the following corner cases:
     * <ul>
     *   <li>{@code auth} is invalid, as stated in {@link io.sustc.service.UserService#deleteAccount(AuthInfo, long)}</li>
     *   <li>{@code followeeMid} is invalid (<= 0 or not found)</li>
     * </ul>
     * If any of the corner case happened, {@code false} shall be returned.
     */

    @Override
    public boolean follow(AuthInfo auth, long followeeMid) {
        long mid = getMidByAuth(auth);
        if (mid == followeeMid) return false;
        //if (!isUserExist(followeeMid)) return false;
        if (isAuthValid(auth) == 0) return false;

        String sql = "INSERT INTO users_follow (follower_mid, followee_mid) VALUES (?, ?)";

        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, mid);
            stmt.setLong(2, followeeMid);

            int affectedRows = stmt.executeUpdate();
            if (affectedRows == 0) {
                return false;
            } else {
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Follow failed.", e);
        }
    }

        /**
     * Gets the required information (in DTO) of a user.
     *
     * @param mid the user to be queried
     * @return {@code mid}s person Information
     * @apiNote You may consider the following corner cases:
     * <ul>
     *   <li>{@code mid} is invalid (<= 0 or not found)</li>
     * </ul>
     * If any of the corner case happened, {@code null} shall be returned.
     */
    @Override
    public UserInfoResp getUserInfo(long mid) {

        //if (!isUserExist(mid)) return null;

        UserInfoResp userInfoResp = new UserInfoResp();

        String str = "SELECT * FROM users WHERE mid = ?";

        String sql1 = "SELECT follower_mid FROM users_follow WHERE followee_mid = ?";
        String sql2 = "SELECT followee_mid FROM users_follow WHERE follower_mid = ?";
        String sql3 = "SELECT bv FROM users_watch WHERE mid = ?";
        String sql4 = "SELECT bv, \"like\", collect FROM videos WHERE ? = ANY (\"like\") OR ? = ANY (collect)";
        String sql5 = "SELECT bv FROM videos WHERE owner_mid = ?";

        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(str)) {
            stmt.setLong(1, mid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                userInfoResp.setMid(rs.getLong("mid"));
                userInfoResp.setCoin(rs.getInt("coin"));
                userInfoResp.setFollowing(fetchUserRelations(conn, sql2, mid));
                userInfoResp.setFollower(fetchUserRelations(conn, sql1, mid));
                userInfoResp.setWatched(fetchUserWatch(conn, sql3, mid));
                userInfoResp.setLiked(fetchVideoRelations(conn, sql4, "like", mid));
                userInfoResp.setCollected(fetchVideoRelations(conn, sql4, "collect", mid));
                userInfoResp.setPosted(fetchUserPost(conn, sql5, mid));
            } else {
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Get user info failed.", e);
        }


        return userInfoResp;
    }

    private long[] fetchUserRelations(Connection conn, String sql, long mid) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, mid);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                ids.add(rs.getLong( 1));
            }
        }
        return ids.stream().mapToLong(Long::longValue).toArray();
    }

    private String[] fetchUserWatch(Connection conn, String sql, long mid) throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, mid);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                ids.add(rs.getString( 1));
            }
        }
        return ids.toArray(new String[0]);
    }

    private String[] fetchVideoRelations(Connection conn, String sql, String columnName, long mid) throws SQLException {
        List<String> bvs = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, mid);
            stmt.setLong(2, mid);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                // like 和 collect 是 long[] 类型
                String[] ids = rs.getString(columnName).split(",");
                for (String id : ids) {
                    id = fixString(id);
                    if (Long.parseLong(id) == mid) {
                        bvs.add(rs.getString("bv"));
                    }
                }

            }
        }
        return bvs.toArray(new String[0]);
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

    private String[] fetchUserPost(Connection conn, String sql, long mid) throws SQLException {
            List<String> bvs = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, mid);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    bvs.add(rs.getString( 1));
                }
            }
            return bvs.toArray(new String[0]);
    }

    private boolean isUserExist (long mid) {
        if (mid <= 0) return false;
        String sql = "SELECT * FROM users WHERE mid = ?";
        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, mid);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return true;
            } else {
                return false;
            }
        } catch (SQLException e) {
            throw new RuntimeException("User do not exist", e);
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

    private boolean isDeleteValid (AuthInfo auth, long mid) {
        String sql = "SELECT identity FROM users WHERE mid = ?";

        try (Connection conn = dataSource.getConnection();
        PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, auth.getMid());

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                if (rs.getString("identity").equals("USER") && auth.getMid() != mid) {
                    return false;
                } else if (rs.getString("identity").equals("SUPERUSER") && auth.getMid() != mid) {
                    return false;
                } else {
                    return true;
                }
            } else {
                return true;
            }
        } catch (SQLException e) {
            throw new RuntimeException("No authority to delete user.", e);
        }
    }
}
