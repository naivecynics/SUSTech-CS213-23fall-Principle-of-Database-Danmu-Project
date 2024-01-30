-- CREATE DATABASE sustc WITH ENCODING = 'UTF8' LC_COLLATE = 'C' TEMPLATE = template;
-- DROP DATABASE sustc;
-- drop
-- DROP SCHEMA public CASCADE;
-- CREATE SCHEMA public;

-- tables
CREATE TABLE users (
    mid BIGINT PRIMARY KEY,
    name VARCHAR(255) UNIQUE NOT NULL,
    sex VARCHAR(50),
    birthday VARCHAR(20),
    level INT DEFAULT 0,
    sign TEXT,
    identity VARCHAR(50),
    password VARCHAR(255) NOT NULL,
    qq VARCHAR(100),
    wechat VARCHAR(100) ,
    coin BIGINT DEFAULT 0,
    deleted BOOLEAN DEFAULT FALSE
);

CREATE TABLE videos (
    bv VARCHAR(255) PRIMARY KEY,
    title VARCHAR(255),
    owner_mid BIGINT,
    owner_name VARCHAR(255),
    commit_time TIMESTAMP,
    review_time TIMESTAMP,
    public_time TIMESTAMP,
    duration FLOAT,
    description TEXT,
    view_times BIGINT DEFAULT 0,
    "like" BIGINT[],
    coin BIGINT[],
    collect BIGINT[],
    reviewer BIGINT,
    reviewed BOOLEAN DEFAULT TRUE,
    deleted BOOLEAN DEFAULT FALSE
);

CREATE TABLE danmus (
    id SERIAL PRIMARY KEY,
    bv VARCHAR(255),
    mid BIGINT,
    content TEXT,
    time FLOAT,
    liked_by BIGINT[],
    post_time TIMESTAMP,
    deleted BOOLEAN DEFAULT FALSE
);

CREATE TABLE users_follow (
    id SERIAL PRIMARY KEY,
    follower_mid BIGINT,
    followee_mid BIGINT,
    deleted BOOLEAN DEFAULT FALSE
);

CREATE TABLE users_watch (
    id SERIAL PRIMARY KEY,
    mid BIGINT,
    bv VARCHAR(255),
    view_time FLOAT
);

-- index
CREATE INDEX uploader_index ON videos(owner_mid);
CREATE INDEX danmus_index ON danmus(bv);
CREATE INDEX follower ON users_follow(follower_mid);
CREATE INDEX followee ON users_follow(followee_mid);
CREATE INDEX watch_index ON users_watch(bv);
CREATE INDEX watch_mid on users_watch(mid);
