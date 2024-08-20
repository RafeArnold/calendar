CREATE TABLE users
(
    user_id           INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    google_subject_id TEXT    NOT NULL UNIQUE,
    email_address     TEXT    NOT NULL UNIQUE
);

DROP TABLE opened_days;

CREATE TABLE opened_days
(
    date    DATE      NOT NULL,
    user_id INTEGER   NOT NULL,
    opened  TIMESTAMP NOT NULL,
    PRIMARY KEY (date, user_id),
    FOREIGN KEY (user_id) REFERENCES users (user_id)
);
