-- V1 to V5 are applied and frozen.

-- What the parent tells us on the screen after sign-up. Both are null until then.
-- relation: who the member is to the child (mother, father, grandparent, other).
-- age_group: the member's own age (20s, 30s, 40s, 50s_plus). Optional, used for recommending plays only.
ALTER TABLE users ADD COLUMN relation VARCHAR(20) NULL;
ALTER TABLE users ADD COLUMN age_group VARCHAR(10) NULL;
