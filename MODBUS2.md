# 将 Modbus 配置，生成具体的 Modbus 服务

## 一、设计思想

MODBUS.md 的设计思想是：将 Modbus 配置生成具体的设备实例。
优点：
* 复用设备实例定义，读属性、写属性、执行方法，均可使用通用的设备接口（DeviceResource）。
缺点：
* 使用设备拦截器拦截指令时，步骤繁多，且需要读几次数据库。

本文档的设计思想是：将 Modbus 配置，生成具体的 Modbus 服务
优点
* 简单，只需访问一次数据库（ModbusServiceRepository），映射的规则都在ModbusService中。
缺点
* 需要单独设计一套API：ModbusServiceResource

## 二、示例

./modbus-service-example.json

## 三、具体实现

1. 参考示例文件，使用 ModbusService 来描述，然后完成 ModbusServiceRepository。
2. 完成 ModbusServiceResource：
   * ModbusService 的创建、删除、修改、查找（根据组织查找、根据父设备的DID查找，指定ID查找）。 
   * ModbusService 方法的调用。