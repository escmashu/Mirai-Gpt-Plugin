package org.gptbot.LiseningEvent

import net.mamoe.mirai.event.subscribeMessages
import net.mamoe.mirai.message.data.content
import org.gptbot.ChatBot.BotBasic.apikey
import org.gptbot.ChatBot.BotBasic.bot
import org.gptbot.ChatBot.BotBasic.path_Apikey
import org.gptbot.ChatBot.BotBasic.tokenPattern3
import org.gptbot.ChatBot.BotBasic.tokenPattern4
import org.gptbot.ChatBot.BotBasic.tokenPatternX
import java.io.File


fun keySet(key : String){
    bot.eventChannel.subscribeMessages {
        startsWith(key) reply {
            val getmsg = message.content.substring(key.length).replace(" ","").replace("\n","")
            if (!getmsg.matches(tokenPattern3) and !getmsg.matches(tokenPattern4) and !getmsg.matches(tokenPatternX)) {
                "格式错误重新配置"
            }else {
                File(path_Apikey).writeText(getmsg)
                apikey = File(path_Apikey).readText()
                "配置成功"
            }
        }
    }
}

