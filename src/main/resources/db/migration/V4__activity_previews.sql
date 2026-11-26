-- V1 to V3 are applied and frozen.

-- Sample pages an admin uploads for an activity, shown before buying. Up to five images, in order.
CREATE TABLE activity_previews (
    activity_id BIGINT       NOT NULL,
    position    INT          NOT NULL,
    path        VARCHAR(255) NOT NULL,
    PRIMARY KEY (activity_id, position),
    CONSTRAINT fk_activity_previews_activity FOREIGN KEY (activity_id) REFERENCES activities (id) ON DELETE CASCADE
);
