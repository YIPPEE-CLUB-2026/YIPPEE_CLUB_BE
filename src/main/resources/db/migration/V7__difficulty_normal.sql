-- V1 to V6 are applied and frozen.

-- A middle difficulty between easy and hard. The rule is replaced; no stored value changes.
ALTER TABLE activities DROP CONSTRAINT ck_activities_difficulty;
ALTER TABLE activities ADD CONSTRAINT ck_activities_difficulty CHECK (difficulty IN ('easy', 'normal', 'hard'));
