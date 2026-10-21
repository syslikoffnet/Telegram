package org.telegram.tgnet;

/**
 * Pengram: {@code auth.importBotAuthorization} — вход в аккаунт бота по его
 * токену из BotFather.
 *
 * <p>Метод лежит отдельным файлом, а не в {@link TLRPC}: тот генерируется из
 * схемы и переписывается целиком при каждом обновлении апстрима, так что
 * добавленное туда молча пропало бы при следующем слиянии.
 */
public class TL_auth_importBotAuthorization extends TLObject {

    public static final int constructor = 0x67a3ff2c;

    /** зарезервировано схемой; сервер требует ноль */
    public int flags;
    public int api_id;
    public String api_hash;
    public String bot_auth_token;

    @Override
    public TLObject deserializeResponse(InputSerializedData stream, int constructor, boolean exception) {
        return TLRPC.auth_Authorization.TLdeserialize(stream, constructor, exception);
    }

    @Override
    public void serializeToStream(OutputSerializedData stream) {
        stream.writeInt32(constructor);
        stream.writeInt32(flags);
        stream.writeInt32(api_id);
        stream.writeString(api_hash);
        stream.writeString(bot_auth_token);
    }
}
