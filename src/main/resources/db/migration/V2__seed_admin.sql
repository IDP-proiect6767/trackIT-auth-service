-- DEV ONLY: change password before deploying anywhere public
INSERT INTO auth.users (username, email, password_hash, role, enabled)
VALUES (
    'admin',
    'admin@trackit.local',
    '$2a$12$tQNBjxRHCTsPPgZ/YLVK6.M1bq.MUfyZzXMEKkIqihtJNHBhJjN9O',
    'ADMIN',
    TRUE
);
