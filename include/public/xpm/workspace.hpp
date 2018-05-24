/**
 * An experimental workspace
 */

#ifndef XPM_WORKSPACE_H
#define XPM_WORKSPACE_H

#include <string>
#include <queue>
#include <unordered_map>
#include <map>
#include <unordered_set>
#include <vector>
#include <mutex>

#include <xpm/json.hpp>
#include <xpm/common.hpp>
#include <xpm/filesystem.hpp>

namespace SQLite { class Database; }

namespace xpm {

class Launcher;
class Resource;
class Workspace;
class CommandLine;
struct JobPriorityComparator;
class CounterDependency;

typedef std::uint64_t ResourceId;

/**
 * A dependency between resources
 */
class Dependency : NOSWIG(std::enable_shared_from_this<Dependency>,) public Outputable {
public:
  Dependency(std::shared_ptr<Resource> const & origin);
  virtual ~Dependency();

  /// Sets the dependent
  void target(std::shared_ptr<Resource> const & resource);

  /// Is the dependency satisfied?
  virtual bool satisfied() = 0;

  /// Check the status and update the dependent if needed
  void check();

protected:
  /// To string
  virtual void output(std::ostream &out) const override;

private:
  // The origin
  std::shared_ptr<Resource> _origin;

  // The dependent
  std::shared_ptr<Resource> _target;

  /// Old satisfaction status
  bool _oldSatisfied;
  
  /// Resource mutex
  std::mutex _mutex;

  friend class Resource;
  friend class Workspace;
};

/// Base class for any resource
class Resource : NOSWIG(public std::enable_shared_from_this<Resource>,) public Outputable {
public:
  Resource();
  ~Resource();

  /// Finish the initialization
  virtual void init();

  /// Get the workspace resource ID
  inline ResourceId getId() const { return _resourceId; }

  void addDependent(std::shared_ptr<Dependency> const & dependency);
  void removeDependent(std::shared_ptr<Dependency> const & dependency);

  /**
   * Create a simple dependency from this resource
   * 
   * Subclasses might propose more specific dependency creation
   */
  virtual std::shared_ptr<Dependency> createDependency() = 0;

  /// Returns the resource as JSON (to include in job parameters)
  virtual nlohmann::json toJson() const { return nullptr; }
protected:
  /// Resource that depend on this one to be completed
  std::vector<std::weak_ptr<Dependency>> _dependents;

  /// Resource mutex
  std::mutex _mutex;

  /// Resource id
  ResourceId _resourceId;

  /// Signals a change in a dependency
  virtual void dependencyChanged(Dependency & dependency, bool satisfied);

  friend class Dependency;

  virtual void output(std::ostream &out) const override;
};



/// Tokens are used to limit the number of running jobs
class Token : public Resource {
};


/// A simple token based on counts
class CounterToken : public Token {
public:
  typedef uint32_t Value;

  /// Initialize the token
  CounterToken(Value limit);
  
  /// Set the limit
  void limit(Value _limit);

  /// Create a new dependency
  std::shared_ptr<Dependency> createDependency(Value count);

  virtual std::shared_ptr<Dependency> createDependency() override;

private:
  friend class CounterDependency;
    /**
     * Maximum number of tokens available
     */
    Value _limit;

    /**
     * Number of used tokens
     */
    Value _usedTokens;
};

/**
 * Resource state
 * 
 * Possible paths:
 * - WAITING <-> READY -> RUNNING -> { ERROR, DONE } -> { WAITING, READY }
 * - {WAITING, READY} -> ON_HOLD -> READY
 */
enum struct JobState {
  /// For a job only: the job is waiting dependencies status be met
  WAITING,

  /// For a job only: the job is waiting for an available thread status launch it
  READY,

  /// For a job only: The job is currently running
  RUNNING,

  /**
   * The job ran but did not complete, the data was not generated, or dependencies 
   * failed to generate
  */
  ERROR,

  /// Completed (for a job) or generated (for a data resource)
  DONE
};

/// String representation of a job state
std::ostream &operator<<(std::ostream & out, JobState const & state);


extern PathTransformer EXIT_CODE_PATH;
extern PathTransformer LOCK_PATH;
extern PathTransformer LOCK_START_PATH;
extern PathTransformer DONE_PATH;
extern PathTransformer PID_PATH;


/// Base class for jobs
class Job : public Resource {
public:
  Job(Path const & locator, std::shared_ptr<Launcher> const & launcher);

  /// Adds a new dependency
  void addDependency(std::shared_ptr<Dependency> const & dependency);

  /// The locator
  Path const & locator() { return _locator; }

  /// Returns true if the job is ready to run
  bool ready() const;

  /// Get the current state
  JobState state() const;

  /// Run the job
  virtual void run() = 0;

  /// Get a dependency to this resource
  std::shared_ptr<Dependency> createDependency() override;

  /// Get a JSON representation of this resource
  virtual nlohmann::json toJson() const override;

  /// Get the path to the output stream
  Path stdoutPath() const;

  /// Get the path to the error stream
  Path stderrPath() const;

  /// Get the path to the lock start path
  Path pathTo(std::function<Path(Path const &)> f) const;
protected:
  friend class Workspace;
  friend struct JobPriorityComparator;

  /// Signals a dependency change
  virtual void dependencyChanged(Dependency & dependency, bool satisfied) override;

  /// Called when the job is completed
  void jobCompleted();

  /// The workspace
  std::shared_ptr<Workspace> _workspace;

  /// Main identifier of the task
  Path _locator;

  /// The launcher used for this task
  std::shared_ptr<Launcher> _launcher;

  /// The dependencies of this task
  std::vector<std::shared_ptr<Dependency>> _dependencies;

  /// Submission time
  std::time_t _submissionTime;

  /// Number of dependencies that are not satisifed
  size_t _unsatisfied;

  /// Resource state
  JobState _state;
};

/// A command line job
class CommandLineJob : public Job {
public:
  CommandLineJob(Path const & locator, 
    std::shared_ptr<Launcher> const & launcher,
    std::shared_ptr<CommandLine> const & command);
  virtual void run() override;
  virtual void init() override;
private:
  std::shared_ptr<CommandLine> _command;
};

/// Defines the priority between two jobs
struct JobPriorityComparator {
  bool operator()( std::shared_ptr<Job> const & lhs, std::shared_ptr<Job> const & rhs ) const;

};

/** 
 * Workspace tracking resources, jobs and scheduling
 */
class Workspace NOSWIG(: public std::enable_shared_from_this<Workspace>) {
public:
  /// Creates a work space
  Workspace();

  /// Creates a new work space with a given path
  Workspace(std::string const &path);

  /// Destructor
  virtual ~Workspace();

  /// Submit a job
  void submit(std::shared_ptr<Job> const & job);

  /// Get an iterator for a key
  NOSWIG(std::map<std::string, std::string>::const_iterator find(std::string const &key) const;)

  /// Get the basepath
  Path const workdir() const;

  /// Sets a variable
  void set(std::string const &key, std::string const &value);

  /// Sets a variable with a ns
  void set(std::string const &ns, std::string const &key, std::string const &value);

  /// Gets a variable given a fully qualified name
  std::string get(std::string const &key) const;

  /// Checks if the variable exists
  bool has(std::string const &key) const;

  /// Set the current workspace
  void current();

  /// Current workspace 
  static std::shared_ptr<Workspace> currentWorkspace();

  /// Experiment
  void experiment(std::string const & name);

  /// Wait that all tasks are completed
  static void waitUntilTaskCompleted();

  /// Notify that a job finished
  void jobFinished(Job const &job);

private:
  /// Working directory path
  Path _path;

  /// Current experiment name
  std::string _experiment;

  /// All the jobs
  std::unordered_map<Path, std::shared_ptr<Job>> _jobs;

  /// Count the number of waiting jobs
  std::unordered_set<Job const *> waitingJobs;

  /// List of active workspaces
  static std::unordered_set<Workspace *> activeWorkspaces;

  /// The variables for this workspace
  std::map<std::string, std::string> _variables;

  /// State mutex
  std::mutex _mutex;

  /// SQL Lite
  std::unique_ptr<SQLite::Database> _db;
};


}

#endif