-----------------------------------------------------------------------------------------------
--                                                                                           --
-- Upgrade of db schema from version 1.2 to 1.3                                           --
--                                                                                           --
-----------------------------------------------------------------------------------------------

ALTER TABLE alert ADD COLUMN links varchar;

