--
-- Connectors and shares
--


CREATE TABLE Connectors (
  id IDENTITY,
  type BIGINT        NOT NULL,
  uri  VARCHAR(4096) NOT NULL,
  data BLOB          NOT NULL
);

CREATE UNIQUE INDEX ConnectorURI ON Connectors (uri);

CREATE TABLE NetworkShares (
  id IDENTITY,
  hostname VARCHAR(256) NOT NULL,
  name     VARCHAR(256) NOT NULL
);

CREATE TABLE NetworkShareAccess (
  share     BIGINT        NOT NULL,
  connector BIGINT        NOT NULL,
  path      VARCHAR(4096) NOT NULL,
  priority  INT DEFAULT 0 NOT NULL,

  PRIMARY KEY (share, connector),
  FOREIGN KEY (share) REFERENCES NetworkShares
    ON DELETE CASCADE,
  FOREIGN KEY (connector) REFERENCES Connectors
    ON DELETE CASCADE
);

--
-- Resources
--

CREATE TABLE Resources (
  id IDENTITY,
  path      VARCHAR(4096),
  connector BIGINT,
  status    INT           NOT NULL,
  -- Used to check if a notification has been done after XPM has been stopped
  oldStatus INT           NOT NULL,
  type      BIGINT,
  priority  INT DEFAULT 0 NOT NULL,
  data      BLOB          NOT NULL,

  FOREIGN KEY (connector) REFERENCES Connectors
    ON DELETE RESTRICT
);

CREATE INDEX resources_index ON resources (status);
CREATE UNIQUE INDEX resources_path ON resources (path);
CREATE INDEX resources_priority ON resources (priority);

-- Token resource
CREATE TABLE TokenResources (
  id    BIGINT NOT NULL PRIMARY KEY,
  limit INT    NOT NULL,
  used  INT    NOT NULL,
  FOREIGN KEY (id) REFERENCES Resources
    ON DELETE CASCADE
);

-- Job resource
CREATE TABLE Jobs (
  id          BIGINT NOT NULL PRIMARY KEY,
  submitted   TIMESTAMP,
  start       TIMESTAMP,
  end         TIMESTAMP,
  unsatisfied INT    NOT NULL,
  holding     INT    NOT NULL,
  priority    INT    NOT NULL,
  progress    DOUBLE NOT NULL,

  FOREIGN KEY (id) REFERENCES Resources
    ON DELETE CASCADE
);

-- Directories and/or files associated to resources
-- Used for cleanup
CREATE TABLE ResourcePaths (
  id   BIGINT        NOT NULL PRIMARY KEY,
  path VARCHAR(4096) NOT NULL,

  FOREIGN KEY (id) REFERENCES Resources
    ON DELETE CASCADE
);

--
-- Other
--



--- A lock

CREATE TABLE Locks (
  id IDENTITY,
  type BIGINT NOT NULL,
  data BLOB   NOT NULL
);

-- Dependencies between resources

CREATE TABLE Dependencies (
  fromId BIGINT   NOT NULL,
  toId   BIGINT   NOT NULL,
  type   BIGINT   NOT NULL,
  status SMALLINT NOT NULL,
  -- The data
  data   BLOB     NOT NULL,
  lock   BIGINT,

  -- Primary key
  PRIMARY KEY (fromId, toId),

  -- Foreign key for the source (restricting deletion)
  FOREIGN KEY (fromId) REFERENCES Resources
    ON DELETE RESTRICT,
  FOREIGN KEY (toId) REFERENCES Resources
    ON DELETE CASCADE,
  FOREIGN KEY (lock) REFERENCES Locks
    ON DELETE SET NULL
);

--- Token dependencies

CREATE TABLE TokenDependencies (
  fromId BIGINT        NOT NULL,
  toId   BIGINT        NOT NULL,
  tokens INT DEFAULT 1 NOT NULL,

  PRIMARY KEY (fromId, toId),
  FOREIGN KEY (fromId, toId) REFERENCES Dependencies
    ON DELETE CASCADE
);

-- Ensures that shares are not removed if a lock references it
CREATE TABLE LockShares (
  lock  BIGINT NOT NULL,
  share BIGINT NOT NULL,

  -- do not delete a share if it is referenced
  FOREIGN KEY (lock) REFERENCES Locks
    ON DELETE CASCADE,
  FOREIGN KEY (share) REFERENCES NetworkShares
    ON DELETE RESTRICT
);

--
-- Process
--


CREATE TABLE Processes (
  resource  BIGINT NOT NULL,
  -- Type of the resource
  type      BIGINT NOT NULL,
  connector BIGINT,
  pid       VARCHAR(255),
  data      BLOB   NOT NULL,

  -- One process per resource
  PRIMARY KEY (resource),

  FOREIGN KEY (resource) REFERENCES Resources
    ON DELETE RESTRICT
    ON UPDATE CASCADE,
  FOREIGN KEY (connector) REFERENCES Connectors
    ON DELETE RESTRICT
    ON UPDATE CASCADE
);

-- Locks taken by processes
CREATE TABLE ProcessLocks (
  process BIGINT NOT NULL,
  lock    BIGINT NOT NULL,

  -- Ensures that no locks are left behind
  CONSTRAINT ProcessLocks_process FOREIGN KEY (process) REFERENCES Processes
    ON DELETE RESTRICT,

  -- Removing a lock will remove the process lock
  CONSTRAINT ProcessLocks_lock FOREIGN KEY (lock) REFERENCES Locks
    ON DELETE CASCADE
);

--
-- Experiments
--

-- Table containing the list of experiments with their timestamps
CREATE TABLE Experiments (
  id IDENTITY,
  name      VARCHAR(256)                        NOT NULL,
  timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX experiment_name ON Experiments (name, timestamp);


-- Table containing the list of tasks together with their
-- dependencies
CREATE TABLE ExperimentTasks (
  id IDENTITY,
  identifier VARCHAR(256) NOT NULL,
  experiment BIGINT       NOT NULL,

  FOREIGN KEY (experiment) REFERENCES Experiments
    ON DELETE CASCADE,
  FOREIGN KEY (parent) REFERENCES ExperimentTasks
    ON DELETE CASCADE
);

CREATE TABLE ExperimentHierarchy (
  parent BIGINT NOT NULL,
  child  BIGINT NOT NULL,
  FOREIGN KEY (parent) REFERENCES ExperimentTask
    ON DELETE CASCADE,
  FOREIGN KEY (child) REFERENCES ExperimentTasks
    ON DELETE CASCADE
);


CREATE TABLE ExperimentResources (
  task     BIGINT NOT NULL,
  resource BIGINT NOT NULL,

  FOREIGN KEY (task) REFERENCES ExperimentTasks
    ON DELETE CASCADE,
  FOREIGN KEY (resource) REFERENCES Resources
    ON DELETE RESTRICT
);


