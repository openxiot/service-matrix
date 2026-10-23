package cc.openxiot.matrix.db.mongo;

import jakarta.inject.Singleton;
import org.bson.BsonReader;
import org.bson.BsonType;
import org.bson.BsonWriter;
import org.bson.codecs.BsonTypeClassMap;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecConfigurationException;
import org.bson.codecs.pojo.PropertyCodecProvider;
import org.bson.codecs.pojo.PropertyCodecRegistry;
import org.bson.codecs.pojo.TypeWithTypeParameters;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 让 <b>声明成 {@code Object} 的字段能落库</b>：按运行时类型编码、按 BSON 类型解码。
 *
 * <h3>为什么必须有它</h3>
 *
 * <p>驱动的 {@code AutomaticPojoCodec} 是**按字段的声明类型**取 codec 的
 * （{@code LazyPropertyModelCodec} → {@code PropertyCodecRegistry}）。声明成 {@code Object} 的字段走到
 * {@code FallbackPropertyCodecProvider} 时，它拿 {@code Object.class} 去问注册表 —— 没人认领这个类，
 * 于是编码在第一步就炸：</p>
 *
 * <pre>
 * Failed to encode 'ModbusServiceRegistryHistory'. Encoding 'value' errored with:
 * Can't find a codec for CodecCacheKey{clazz=class java.lang.Object, types=null}.
 * </pre>
 *
 * <p>而「值」这件事天然是异构的：{@link cc.openxiot.matrix.db.modbus.history.ModbusServiceRegistryHistory#value}
 * 是数值 / {@code value-list} 的 description / 位展开的 0|1，声明类型只能是 {@code Object} ——
 * 缺的不是某一条 codec，而是「声明类型 = {@code Object}」这一格的兜底。</p>
 *
 * <p>{@code Map<String, Object>}（{@link cc.openxiot.matrix.db.modbus.history.ModbusServicePollingHistory#fields}、
 * {@link cc.openxiot.matrix.db.space.SpaceEntity#attributes}）本来不会被这条卡住：
 * 驱动的 {@code MapPropertyCodecProvider} 对「值类型是 {@code Object} 的 Map」另有一条兜底
 * （退回去用裸 {@code Map} 的 codec，那个 codec 自己按运行时类分发）。本 provider 因为排在 Map 之前，
 * 会把那张 Map 的**值槽**接过来，得到的口径与它原来那条一致；map 的值能落库这件事**不是**本类的功劳，
 * 但接过来之后必须仍能落库 —— {@code ObjectPropertyCodecProviderTest} 里两个地图用例钉的就是这个。</p>
 *
 * <h3>怎么被装上</h3>
 *
 * <p>这是驱动自己的扩展点：Quarkus 的 {@code MongoClients.configureCodecRegistry} 会把容器里
 * 所有 {@link PropertyCodecProvider} bean 注册到那个 {@code automatic(true)} 的 {@code PojoCodecProvider} 上，
 * 且驱动把它们排在自带的那几个（Collection / Map / Enum / Fallback）**之前**。
 * 本类因此不需要任何额外配置，也不要把 BSON 类型漏进业务实体。</p>
 *
 * <p>它找的是「容器里所有 {@code PropertyCodecProvider}」这条查找（{@code Instance<PropertyCodecProvider>}），
 * 而这个注解不是可有可无的：ArC 正是靠那条查找把本类判成 unremovable 才没把它删掉
 * （本机 {@code @QuarkusTest} 开 DEBUG 能看到 {@code Unremovable - programmatic lookup: ... ObjectPropertyCodecProvider}）。
 * 换作用域注解、或哪天改成不是 bean，都得重新确认这一条。</p>
 *
 * <h3>口径</h3>
 *
 * <ul>
 *   <li><b>只认声明成 {@code Object} 的字段</b>，其余类型一律返回 {@code null} 交还给驱动 ——
 *       声明成 {@code Number} / 接口 / 泛型变量的字段该报错就报错，不要在这里被悄悄吞掉。</li>
 *   <li>编码按**运行时**类取 codec，解码按**BSON 类型**取（{@code BsonTypeClassMap}）——
 *       与驱动自己的 {@code DocumentCodec} / {@code MapCodec} / {@code IterableCodec} 同一口径。</li>
 *   <li>因此读回来的 Java 类由 BSON 类型决定，而不是写进去时那个对象：
 *       {@code int32 → Integer}、{@code int64 → Long}、{@code double → Double}、内嵌文档 {@code → Document}。
 *       拿它与旧值比对时**必须容忍数值类型差异** —— {@code ModbusHistoryPolicy.sameValue} 就是按
 *       {@code doubleValue()} 比的，不要另写一套等值判断。</li>
 *   <li>二进制值按 {@code BsonTypeClassMap} 解成 {@code Binary}；驱动那条裸 MapCodec 内的
 *       UUID 子类型特判（{@code ContainerCodecHelper}）不在这里。工程里 {@code Object} 槽位没有 UUID 值，
 *       真出现了再回来看这一条。</li>
 * </ul>
 */
@Singleton
public final class ObjectPropertyCodecProvider implements PropertyCodecProvider {

    /** BSON 类型 → Java 类，驱动解码时用的那张默认表（`DocumentCodec` 等用的也是它）。 */
    private static final BsonTypeClassMap BSON_TYPE_TO_CLASS = new BsonTypeClassMap();

    @Override
    @SuppressWarnings("unchecked")
    public <T> Codec<T> get(TypeWithTypeParameters<T> type, PropertyCodecRegistry registry) {
        if (type.getType() != Object.class) {
            return null;
        }
        return (Codec<T>) new RuntimeTypeCodec(registry, BSON_TYPE_TO_CLASS);
    }

    /**
     * 一个 {@code Object} 字段的 codec：写的时候看值是什么类，读的时候看库里是什么 BSON 类型。
     *
     * <p>实例由驱动按「属性的声明类型」创建一次并缓存（{@code PropertyModel.cachedCodec}），
     * 所以这里的按类缓存只服务一个字段，不必担心跨字段串味。</p>
     */
    private static final class RuntimeTypeCodec implements Codec<Object> {
        private final PropertyCodecRegistry registry;

        private final BsonTypeClassMap bsonTypeToClass;

        private final ConcurrentMap<Class<?>, Codec<Object>> codecs = new ConcurrentHashMap<>();

        private RuntimeTypeCodec(PropertyCodecRegistry registry, BsonTypeClassMap bsonTypeToClass) {
            this.registry = registry;
            this.bsonTypeToClass = bsonTypeToClass;
        }

        @Override
        public void encode(BsonWriter writer, Object value, EncoderContext encoderContext) {
            if (value == null) {
                writer.writeNull();
                return;
            }
            encoderContext.encodeWithChildContext(codecOf(value.getClass()), writer, value);
        }

        @Override
        public Object decode(BsonReader reader, DecoderContext decoderContext) {
            BsonType bsonType = reader.getCurrentBsonType();
            if (bsonType == BsonType.NULL) {
                reader.readNull();
                return null;
            }
            return decoderContext.decodeWithChildContext(codecOf(classOf(bsonType)), reader);
        }

        @Override
        public Class<Object> getEncoderClass() {
            return Object.class;
        }

        /**
         * 取某个 Java 类的 codec，走**属性**注册表（它查不到时会落到兜底那一格，拿这个类去问整张注册表）。
         *
         * <p>问的就是「这个类、不带类型参数」：标量直接对上；集合 / Map 在属性注册表里认不出
         * （它们的 provider 要类型参数才肯接），落到兜底后拿到的是 {@code IterableCodec} / {@code MapCodec} ——
         * 那两个自己按运行时类（编码）和 BSON 类型（解码）分发，于是嵌套数组 / 内嵌文档自然递归下去。</p>
         */
        @SuppressWarnings("unchecked")
        private Codec<Object> codecOf(Class<?> clazz) {
            Codec<Object> cached = codecs.get(clazz);
            if (cached != null) {
                return cached;
            }
            Codec<?> resolved = registry.get(new RawType<>(clazz));
            if (resolved == null) {
                throw new CodecConfigurationException("no codec for the runtime type " + clazz.getName());
            }
            codecs.put(clazz, (Codec<Object>) resolved);
            return (Codec<Object>) resolved;
        }

        private Class<?> classOf(BsonType bsonType) {
            try {
                return bsonTypeToClass.get(bsonType);
            } catch (IllegalArgumentException e) {
                // NULL 上面已经拦掉；END_OF_DOCUMENT 之类不该出现在「一个值」的位置上
                throw new CodecConfigurationException("cannot decode an Object from BSON type " + bsonType);
            }
        }
    }

    /**
     * 「这个类，不带任何类型参数」。
     *
     * <p>驱动自己的实现（{@code TypeData}）是包私有的、没有对外的构造入口，而这里要问的恰恰是一个裸类。
     * 记成 record 是为了白拿 equals / hashCode —— 那两样是属性注册表缓存键的一部分。</p>
     */
    private record RawType<T>(Class<T> type) implements TypeWithTypeParameters<T> {

        @Override
        public Class<T> getType() {
            return type;
        }

        @Override
        public List<? extends TypeWithTypeParameters<?>> getTypeParameters() {
            return List.of();
        }
    }
}
