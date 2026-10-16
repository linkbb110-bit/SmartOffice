-- Demo password for both users: 123456
INSERT IGNORE INTO sys_user (id, username, password, role, is_deleted)
VALUES
    (1, 'admin', '$2y$10$W8KlNcFrIeRYWLNrSz2f6eEQHtwmpt7QMumpo6aGXqlkWtehkwFn.', 'ADMIN', 0),
    (2, 'user',  '$2y$10$W8KlNcFrIeRYWLNrSz2f6eEQHtwmpt7QMumpo6aGXqlkWtehkwFn.', 'USER', 0);


