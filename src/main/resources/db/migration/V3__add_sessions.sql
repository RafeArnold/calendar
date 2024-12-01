CREATE TABLE sessions
(
    session    VARCHAR(32) NOT NULL PRIMARY KEY,
    user_id    INTEGER     NOT NULL,
    expires_at TIMESTAMP   NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE
);
