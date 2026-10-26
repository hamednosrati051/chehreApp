package ir.inourai.newapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ir.inourai.newapp.data.World

private val GoldSoft = Color(0xFFFBF3DC)
private val GoldDark = Color(0xFF8A5A00)

/** نشان قیمت سکه: قرص طلایی. */
@Composable
fun CoinBadge(coins: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(GoldSoft, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text("🪙 $coins سکه", color = GoldDark, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** کارت دنیا: عکس نمونه + نام + قیمت سکه. */
@Composable
fun WorldCard(world: World, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
    ) {
        AsyncImage(
            model = world.sample_image_url,
            contentDescription = world.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
            contentScale = ContentScale.Crop,
        )
        Column(modifier = Modifier.padding(10.dp)) {
            Text(world.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            CoinBadge(world.coin_price, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
