# 保障数据安全

MLSQL 支持多租户，而且可以访问各种数据源，但传统的大数据授权模式主要是以进程拥有者来进行账号授权的，
而且他们并不统一。为了解决这个问题，MLSQL提供了一套统一的授权和验证体系，用户只要根据MLSQL要求开发一个客户端，
然后--jars带上，就可以很方便的连接已有的权限中心。

MLSQL现阶段最细粒度是表级别，可以支持对各数据源的授权和验证。