-- User ids can be a UUID with dashes (36 chars) as well as a Mongo ObjectId (24 chars).
ALTER TABLE deals
    MODIFY COLUMN creator_id VARCHAR(64) NOT NULL;

ALTER TABLE deal_participants
    MODIFY COLUMN user_id VARCHAR(64) NOT NULL;
