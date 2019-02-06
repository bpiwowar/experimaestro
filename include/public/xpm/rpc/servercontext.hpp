/**
 * Defines the different context for the app (main or experiment context)
 */
#ifndef PROJECT_RPC_SERVERCONTEXT_H
#define PROJECT_RPC_SERVERCONTEXT_H

#include <memory>
#include <unordered_set>

#include <xpm/json.hpp>
#include <Poco/File.h>

namespace Poco::Data {
    class Session;
} // Poco::Data
namespace Poco::Net {
    class WebSocket;
}

namespace xpm {
    class Workspace;
    class Job;
} // xpm

namespace xpm::rpc {

/**
 * Object used to send back messages through the websocket
 */
struct Emitter {
  virtual ~Emitter();
  virtual bool active() = 0;
  virtual void send(nlohmann::json const & j) = 0;
};


class ServerContext {
protected:
    int _port;
    std::string _hostname;
    std::string _htdocs;
    std::unordered_set<Emitter*> _listeners;
public:
    virtual ~ServerContext();

    nlohmann::json handle(std::shared_ptr<Emitter> const & emitter, nlohmann::json &message);
    std::unique_ptr<Poco::File> pidFile() const;

    int port();
    std::string const & host();
    std::string const & htdocs();

    void add(Emitter *);
    void remove(Emitter *);
    void forEach(std::function<void(Emitter&)> );

    virtual void refresh(std::shared_ptr<Emitter> const & emitter) = 0;
    virtual void jobStatusNotification(std::string const & jobId, std::string status) = 0;
    virtual void kill(std::shared_ptr<Emitter> const & emitter, std::string const & jobId) = 0;
};

class MainServerContext : public ServerContext {
public:
    MainServerContext();
    virtual ~MainServerContext();
    
    virtual void refresh(std::shared_ptr<Emitter> const & emitter) override;
    virtual void jobStatusNotification(std::string const & jobId, std::string status) override;
    virtual void kill(std::shared_ptr<Emitter> const & emitter, std::string const & jobId) override;

private:
  std::unique_ptr<Poco::Data::Session> session;
};

class ExperimentServerContext : public ServerContext {
public:
    ExperimentServerContext(Workspace & workspace, std::string const & host, int port, std::string const & htdocs);

    virtual void refresh(std::shared_ptr<Emitter> const & emitter) override;
    virtual void jobStatusNotification(std::string const & jobId, std::string status) override;
    virtual void kill(std::shared_ptr<Emitter> const & emitter, std::string const & jobId) override;
private:
    Workspace & _workspace;
};

} // namespace xpm

#endif