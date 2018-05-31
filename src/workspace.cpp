#include <csignal>
#include <xpm/workspace.hpp>
#include <xpm/commandline.hpp>

#include <xpm/xpm.hpp>
#include <xpm/launchers/launchers.hpp>
#include <xpm/connectors/connectors.hpp>

#include <spdlog/fmt/fmt.h>
#include <__xpm/common.hpp>
#include <__xpm/scriptbuilder.hpp>
#include <SQLiteCpp/SQLiteCpp.h>

DEFINE_LOGGER("xpm.workspace");

namespace xpm {


PathTransformer EXIT_CODE_PATH = [](const Path &path) { return path.withExtension("code"); };
PathTransformer LOCK_PATH = [](const Path &path) { return path.withExtension("lock"); };
PathTransformer LOCK_START_PATH = [](const Path &path) { return path.withExtension("lock.start"); };
PathTransformer DONE_PATH = [](const Path &path) { return path.withExtension("done"); };
PathTransformer PID_PATH = [](const Path &path) { return path.withExtension("pid"); };


// --- Dependency


Dependency::Dependency(ptr<Resource> const & origin) : _origin(origin), _oldSatisfied(false) {}
Dependency::~Dependency() {
  if (_origin) {
    _origin->removeDependent(shared_from_this());
  }
}

void Dependency::output(std::ostream &out) const {
  out << fmt::format("Dep[{} -> {}]", _origin, _target);
}

void Dependency::target(ptr<Resource> const &resource) { _target = resource; }

void Dependency::check() {
  std::unique_lock<std::mutex> lock(_mutex);
  bool s = satisfied();
  LOGGER->info("Dependency {} is {}satisfied (was: {})", *this, s ? "" : "not ", _oldSatisfied);
  if (s != _oldSatisfied) {
    _target->dependencyChanged(*this, s);
  }
}

// --- Resource

Resource::Resource() {
    static size_t RESOURCE_ID = 0;
    _resourceId = ++RESOURCE_ID;
}
Resource::~Resource() {}
void Resource::init() {}

void Resource::addDependent(ptr<Dependency> const & dependency) {
  _dependents.push_back(dependency);
}

void Resource::removeDependent(ptr<Dependency> const & dependency) {
  for(auto it = _dependents.begin(); it != _dependents.end(); ++it) {
    auto ptr= it->lock();
    if (!ptr) {
      _dependents.erase(it);
    } else if (ptr == dependency) {
      _dependents.erase(it);
      return;
    }
  }
}


void Resource::dependencyChanged(Dependency &dependency,
                                    bool satisfied) {
  throw assertion_error(
      "A resource cannot handle a change in dependency directly");
}

void Resource::output(std::ostream &out) const {
  out << _resourceId;
}


// --- Token

CounterToken::CounterToken(uint32_t limit) : _limit(limit), _usedTokens(0) {}
void CounterToken::limit(uint32_t limit) { _limit = limit; }

class CounterDependency : public Dependency {
public:
  CounterDependency(ptr<CounterToken> const & counter, CounterToken::Value value) 
    : Dependency(counter), _counter(counter), _value(value) {}

  virtual bool satisfied() {
    return _counter->_usedTokens + _value <= _counter->_limit;
  }
private:
  ptr<CounterToken> _counter;
  CounterToken::Value _value;
};

ptr<Dependency> CounterToken::createDependency(Value count) {
  return mkptr<CounterDependency>(std::static_pointer_cast<CounterToken>(shared_from_this()), count);
}

ptr<Dependency> CounterToken::createDependency() {
  throw exception("Cannot make a simple dependency from a token");
}

// --- Job

class JobDependency : public Dependency {
  ptr<Job> job;
public:
  JobDependency(ptr<Job> const & job);
  virtual bool satisfied() override;  
};

JobDependency::JobDependency(ptr<Job> const & job) : Dependency(job), job(job) {
}

bool JobDependency::satisfied() {
  return job->state() == JobState::DONE;
}



std::ostream &operator<<(std::ostream & out, JobState const & state) {
  switch(state) {
    case JobState::WAITING: return out << "WAITING";
    case JobState::READY: return out << "READY";
    case JobState::RUNNING: return out << "RUNNING";
    case JobState::DONE: return out << "DONE";
    case JobState::ERROR: return out << "ERROR";
  }
}

Job::Job(Path const &locator, ptr<Launcher> const &launcher)
    : _locator(locator), _launcher(launcher), _unsatisfied(0) {}

Path Job::stdoutPath() const {
  return _locator.withExtension("out");
}

Path Job::stderrPath() const {
  return _locator.withExtension("err");
}

Path Job::pathTo(std::function<Path(Path const &)> f) const {
  return f(_locator);
}

nlohmann::json Job::toJson() const  {
  nlohmann::json j = {};
  j["locator"] = _locator.toString();
  return j;
}

JobState Job::state() const { return _state; }

bool Job::ready() const { return _unsatisfied == 0; }

void Job::dependencyChanged(Dependency &dependency, bool satisfied) {
  {
    std::unique_lock<std::mutex> lock(_mutex);
    _unsatisfied += satisfied ? -1 : 1;
  }

  if (_unsatisfied == 0) {
    LOGGER->info("Job {} is ready to run", *this);
    run();
  }
}

JobState Job::state(JobState newState) {
  JobState old = _state;
  _state = newState;
  return old;
}

void Job::addDependency(ptr<Dependency> const &dependency) {
  // Add the dependency
  _dependencies.push_back(dependency);
  auto self = shared_from_this();
  dependency->target(self);

  // Update the dependency (it is unsatisfied at the start)
  _unsatisfied += 1;
}

ptr<Dependency> Job::createDependency() {
  return mkptr<JobDependency>(std::static_pointer_cast<Job>(shared_from_this()));
}

void Job::jobCompleted() {
  // Notify workspace
  _workspace->jobFinished(*this);

  // Notify dependents 
  for (auto &entry : this->_dependents) {
    entry.lock()->check();
  }
}

// --- Command line job

CommandLineJob::CommandLineJob(xpm::Path const &locator,
                               ptr<Launcher> const &launcher,
                               ptr<CommandLine> const &command)
    : Job(locator, launcher), _command(command) {
}

void CommandLineJob::parameters(std::shared_ptr<Parameters> const & parameters) {
  _parameters = parameters;
}

std::shared_ptr<Parameters> CommandLineJob::parameters() {
  return _parameters;  
}

void CommandLineJob::init() {
  _parameters->addDependencies(*this, false);
}

void CommandLineJob::run() {
  // Run uses a thread
  std::thread([this]() {
    std::unique_lock<std::mutex> jobLock(_mutex);
    LOGGER->info("Running job {}...", *this);

    auto scriptBuilder = _launcher->scriptBuilder();
    auto processBuilder = _launcher->processBuilder();

    auto & connector = *_launcher->connector();
    auto const donePath = pathTo(DONE_PATH);

    // Check if already done
    auto check = [&] {
      if (connector.fileType(donePath) == FileType::FILE) {
        LOGGER->info("Job {} is already done", *this);
        state(JobState::DONE);
        jobCompleted();
        return true;
      }
      return false;
    };

    // check if done
    if (check()) return;

    // Lock the job and check done again (just in case)
    LOGGER->debug("Making directories job {}...", _locator);
    Path directory = _locator.parent();
    _launcher->connector()->mkdirs(directory, true, false);

    auto lockPath = pathTo(LOCK_PATH);

    auto lock = _launcher->connector()->lock(lockPath);
    if (check()) return;
    
    // Now we can write the script
    scriptBuilder->command = _command;
    scriptBuilder->lockFiles.push_back(lockPath);
    Path scriptPath = scriptBuilder->write(*_workspace, connector, _locator, *this);
    auto startlock = _launcher->connector()->lock(pathTo(LOCK_START_PATH));
    
    LOGGER->info("Starting job {}", _locator);
    processBuilder->environment = _launcher->environment();
    processBuilder->command.push_back(
        _launcher->connector()->resolve(scriptPath));
    processBuilder->stderr = Redirect::file(connector.resolve(directory.resolve({_locator.name() + ".err"})));
    processBuilder->stdout = Redirect::file(connector.resolve(directory.resolve({_locator.name() + ".out"})));

    ptr<Process> process = processBuilder->start();
    state(JobState::RUNNING);
    
    // Avoid to remove the locks ourselves
    startlock->detachState(true);
    lock->detachState(true);

    // Wait for end of execution
    LOGGER->info("Waiting for job {} to finish", _locator);
    int exitCode = process->exitCode();
    state(exitCode == 0 ? JobState::DONE : JobState::ERROR);
    LOGGER->info("Job {} finished with exit code {} (state {})", _locator, exitCode, state());

      
      this->jobCompleted();
  }).detach();
}

// -- Workspace

namespace {
  std::shared_ptr<Workspace> CURRENT_WORKSPACE;
  
  // Use to notify a changed in job status
  std::mutex JOB_CHANGED_MUTEX;
  std::condition_variable JOB_CHANGED;
}


std::unordered_set<Workspace *> Workspace::activeWorkspaces;

bool JobPriorityComparator::operator()(ptr<Job> const &lhs,
                                       ptr<Job> const &rhs) const {
  // Returns true if the lhs should have a lower priority than the rhs
  if (lhs->_unsatisfied != rhs->_unsatisfied)
    return lhs->_unsatisfied > rhs->_unsatisfied;
  return lhs->_submissionTime > rhs->_submissionTime;
}

Workspace::Workspace() : Workspace("") {
}

Workspace::Workspace(std::string const &path) : _path(path) {
  if (!path.empty()) {
    _db = std::unique_ptr<SQLite::Database>(new SQLite::Database(path + "/experimaestro.db", SQLite::OPEN_READWRITE|SQLite::OPEN_CREATE));
  }
  activeWorkspaces.insert(this);
}

Workspace::~Workspace() {
  activeWorkspaces.erase(this);
}


void Workspace::submit(ptr<Job> const &job) {
  std::lock_guard<std::mutex> lock(_mutex);

  // Skip if same path exists
  if (_jobs.find(job->locator()) != _jobs.end()) {
    LOGGER->warn("Job with path {} already exists - skipping new submission",
                 job->locator());
    return;
  }

  waitingJobs.insert(job.get());

  // Add job to list
  job->_submissionTime = std::time(nullptr);
  job->_workspace = shared_from_this();
  _jobs[job->locator()] = job;

  // Register dependencies so that we are notified of changes
  for(auto & dependency: job->_dependencies) {
    dependency->_origin->addDependent(dependency);
    dependency->check();
  }

  // Check if ready
  if (job->_dependencies.empty() && job->ready()) {
    job->run();
  }
}

void Workspace::jobFinished(Job const & job) {
  std::lock_guard<std::mutex> lock(JOB_CHANGED_MUTEX);
  waitingJobs.erase(&job);
  JOB_CHANGED.notify_all();
}

void Workspace::current() {
  CURRENT_WORKSPACE = shared_from_this();
}

std::shared_ptr<Workspace> Workspace::currentWorkspace() {
  return CURRENT_WORKSPACE;
}

Path const Workspace::workdir() const {
  return _path;
}

Path const Workspace::jobsdir() const {
  return _path / "jobs";
}

void Workspace::set(std::string const &key, std::string const &value) {
  _variables[key] = value;
}


void Workspace::set(std::string const &ns, std::string const &key, std::string const &value) {
  _variables[ns + "." + key] = value;
}

std::map<std::string,std::string>::const_iterator Workspace::find(std::string const &key) const {
  size_t last_dot = key.rfind('.');
  std::string name = key.substr(last_dot == std::string::npos ? 0 : last_dot + 1);

  for (;last_dot != std::string::npos; last_dot = key.rfind('.', last_dot - 1)) {
    // Get the key
    std::string _key = last_dot == std::string::npos ? 
        name : key.substr(0, last_dot) + "." + name;

    auto it = _variables.find(_key);
    if (it != _variables.end()) {
      return it;
    }
  }

  // Search for name
  return _variables.find(name);
}

std::string Workspace::get(std::string const &key) const {
  auto it = find(key);
  return it != _variables.end() ? it->second : "";
}

bool Workspace::has(std::string const &key) const {
  return find(key) != _variables.end();
}

void Workspace::experiment(std::string const & name) {
  _experiment = name;
}

namespace {
  bool exitSignal;
  void sigexitHandler(int) {
    LOGGER->warn("Got SIGINT");
    exitSignal = true;
  }
}

void Workspace::waitUntilTaskCompleted() {
  std::signal(SIGINT, &sigexitHandler);

  // Go through all the workspaces
  size_t count = 0;
  do {
    // Count the number of jobs
    count = 0;
    std::unique_lock<std::mutex> lock(JOB_CHANGED_MUTEX);
    for(auto *ws: activeWorkspaces) {
      count += ws->waitingJobs.size();
    }

    if (count > 0) {
      LOGGER->info("Waiting for {} jobs to complete", count);
      JOB_CHANGED.wait(lock);
    }
  } while (count > 0 && !exitSignal);
 
}

} // namespace xpm